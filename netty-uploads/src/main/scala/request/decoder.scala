package unfiltered.netty.request

import unfiltered.netty.ReceivedMessage
import unfiltered.netty.RequestBinding
import unfiltered.netty.ExceptionHandler
import unfiltered.request.POST

import org.jboss.{netty => jnetty}  // 3.x
import jnetty.handler.codec.http.{HttpRequest => NHttpRequest,HttpChunk => NHttpChunk,HttpMessage => NHttpMessage}
import org.jboss.netty.channel._

import io.{netty => ionetty}        // 4.x
import ionetty.handler.codec.http.{HttpPostRequestDecoder => IOHttpPostRequestDecoder}
import ionetty.handler.codec.http.{DefaultHttpDataFactory => IODefaultHttpDataFactory}
import ionetty.handler.codec.http.{InterfaceHttpData => IOInterfaceHttpData}
import ionetty.handler.codec.http.{Attribute => IOAttribute}
import ionetty.handler.codec.http.{FileUpload => IOFileUpload}

/** A PostDecoder wraps a HttpPostRequestDecoder which is available in netty 4 onwards. 
    We implicitly convert a netty 3 HttpRequest to a netty 4 HttpRequest to enable us to use 
    the new multi-part decoding features (until such time as netty 4 is officially released 
    and unfiltered uses it by default). Decoding chunked messages, while supported by netty 4 
    is not implemented here, so use of a HttpChunkAggregator in the handler pipeline is 
    mandatory for now. */
class PostDecoder(req: NHttpRequest, useDisk: Boolean = true) {

  /** Enable implicit conversion between netty 3.x and 4.x. One day this won't be needed any more :) */
  import Implicits._

  import scala.collection.JavaConversions._

  /** Build a post decoder and parse the request. This only works with POST requests. */
  private lazy val decoder = try {
    val factory = new IODefaultHttpDataFactory(useDisk)
    Some(new IOHttpPostRequestDecoder(factory, req))
  } catch {
    /** Would it be more useful to throw errors here? */
    case e: IOHttpPostRequestDecoder.ErrorDataDecoderException => None
    /** GET method. Can't create a decoder. */
    case e: IOHttpPostRequestDecoder.IncompatibleDataDecoderException => None
  }

  /** Whether the request is multi-part */
  def isMultipart: Boolean = decoder.map(_.isMultipart).getOrElse(false)

  /** Returns a collection containing all the parts of the parsed request */
  def items: List[IOInterfaceHttpData] = decoder.map(_.getBodyHttpDatas.toList).getOrElse(List())

  /** Returns a collection of uploaded files found in the parsed request */
  def fileUploads = items collect { case file: IOFileUpload => file }

  /** Returns a collection of all the parts excluding file uploads from the parsed request */
  def parameters = items collect { case param: IOAttribute => param }

  /** Add a received HttpChunk to the decoder */
  def offer(chunk: NHttpChunk) = decoder.map(_.offer(chunk))

  /** Clean all HttpDatas (on Disk) for the current request */
  def cleanFiles = decoder.map(_.cleanFiles)
}

object PostDecoder{
  def apply(req: NHttpRequest, useDisk: Boolean = true): Option[PostDecoder] = {
    val postDecoder = new PostDecoder(req, useDisk)
    postDecoder.decoder match {
      case Some(dec) => Some(postDecoder)
      case _ => None
    }
  }
}

/** Provides storage for state when attached to a ChannelHandlerContext */
private [netty] case class MultiPartChannelState(
  readingChunks: Boolean = false,
  originalReq: Option[NHttpRequest] = None,
  decoder: Option[PostDecoder] = None
)

/** Provides useful defaults for Passing */
object MultipartPlan {
  /** A pass handler serves as a means to forward a request upstream for
   *  unhandled patterns and protocol messages */
  type PassHandler = (ChannelHandlerContext, ChannelEvent) => Unit

  /** A default implementation of a PassHandler which sends the message upstream */
  val DefaultPassHandler = ({ (ctx, event) =>
    event match {
      case me: MessageEvent => ctx.sendUpstream(event)
      case _ => () // we really only care about MessageEvents but need to support the more generic ChannelEvent
    }
   }: PassHandler)
}

/** Enriches a netty plan with multipart decoding capabilities. */
trait AbstractMultiPartDecoder extends CleanUp {
  /** Whether the ChannelBuffer used in decoding is allowed to write to disk */
  protected val useDisk: Boolean = true
  /** Called when there are no more chunks to process. Implementation differs between cycle and async plans */
  protected def complete(ctx: ChannelHandlerContext, e: MessageEvent)

  /** A pass handler that should be supplied when the plan is created. Default is to send upstream */
  def pass: MultipartPlan.PassHandler

  /** Determine whether the intent could handle the request (without executing it). If so execute @param body, otherwise pass */
  protected def handleOrPass(ctx: ChannelHandlerContext, e: MessageEvent, binding: RequestBinding)(body: => Unit)

  /** Provides multipart request handling common to both cycle and async plans. 
      Should be called by onMessageReceived. */
  protected def handle(ctx: ChannelHandlerContext, e: MessageEvent) = {

    /** Maintain state as exlained here:
        http://docs.jboss.org/netty/3.2/api/org/jboss/netty/channel/ChannelHandlerContext.html */
    val channelState = ctx getAttachment match {
      case s: MultiPartChannelState => s
      case _ => MultiPartChannelState()
    }

    e getMessage match {
      case request: NHttpRequest =>
        val msg = ReceivedMessage(request, ctx, e)
        val binding = new RequestBinding(msg)
        binding match {
          // Should match the initial multipart request
          case POST(MultiPart(_)) =>
            // Determine whether the request is destined for this plan's intent and if not, pass
            handleOrPass(ctx, e, binding) {
              // The request is destined for this intent, so start to handle it
              start(request, channelState, ctx, e)
            }
          // The request is not valid for this plan so pass
          case _ => pass(ctx, e)
        }
      // Should match subsequent chunks of the same request
      case chunk: NHttpChunk =>
        channelState.originalReq match {
          // Ensure that multipart handling was started for the request
          case Some(request) => 
            val msg = ReceivedMessage(request, ctx, e)
            val binding = new RequestBinding(msg)
            // Determine whether the chunk is destined for this plan's intent and if not, pass
            handleOrPass(ctx, e, binding) {
              // The chunk is destined for this intent, so handle it
              continue(chunk, channelState, ctx, e)
            }
          case _ => pass(ctx, e)
        }
    }
  }

  /** Sets up for handling a multipart request */
  private def start(request: NHttpRequest, 
                    channelState: MultiPartChannelState,
                    ctx: ChannelHandlerContext, 
                    e: MessageEvent) = {
    if(!channelState.readingChunks) {
      // Initialise the decoder
      val decoder = PostDecoder(request, useDisk)
      // Store the initial state
      ctx.setAttachment(MultiPartChannelState(channelState.readingChunks, Some(request), decoder))
      if(request.isChunked) {
        // Update the state to readingChunks = true
        ctx.setAttachment(MultiPartChannelState(true, Some(request), decoder))
      } else {
        // This is not a chunked request (could be an aggregated multipart request), 
        // so we should have received it all. Behave like a regular netty plan.
        complete(ctx, e)
        cleanUp(ctx)
      }
    } else {
      // Shouldn't get here
      error("HttpRequest received while reading chunks: %s".format(request))
    }
  }

  /** Handles incoming chunks belonging to the original request */
  private def continue(chunk: NHttpChunk,
                       channelState: MultiPartChannelState,
                       ctx: ChannelHandlerContext, 
                       e: MessageEvent) = {
    // Should be reading chunks here
    if(channelState.readingChunks) {
      // Give the chunk to the decoder
      channelState.decoder.map(_.offer(chunk))
      if(chunk.isLast) {
        // This was the last chunk so we can complete
        ctx.setAttachment(channelState.copy(readingChunks=false))
        complete(ctx, e)
        cleanUp(ctx)
      }
    } else {
      // It shouldn't be possible to get here
      error("HttpChunk received when not expected.")
    }
  }
}

trait CleanUp {
  /** Erase the channel state in case the context gets recycled */
  def cleanUp(ctx: ChannelHandlerContext) = ctx.setAttachment(null)

  /** Erase any temporary data that may be on disk */
  def cleanFiles(ctx: ChannelHandlerContext) = channelState(ctx).decoder.map(_.cleanFiles)

  /** Retrieve the channel state */
  def channelState(ctx: ChannelHandlerContext) = ctx getAttachment match {
    case s: MultiPartChannelState => s
    case _ => MultiPartChannelState()
  }
}

/** Ensure any state cleanup is done when an exception is caught */
trait TidyExceptionHandler extends ExceptionHandler with CleanUp { self: SimpleChannelUpstreamHandler =>
  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) = {
    cleanFiles(ctx)
    cleanUp(ctx)
    super.exceptionCaught(ctx, e)
  }
}