package app.gifitup.server.http

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpStatusClass
import io.netty.handler.codec.http.LastHttpContent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Closes a connection that doesn't deliver a complete request (head and body) within [deadlineSeconds] while no request
 * is being answered (#12 round 1, SEC-15). That covers a connection that sends nothing, a partial request line or
 * headers, a body trickled byte by byte, and an idle keep-alive connection after a response. Ktor's own read timeout
 * doesn't: it only fails an active call and otherwise just logs.
 *
 * Sits right after Netty's HTTP codec, so it sees decoded request parts inbound and response parts outbound. It counts
 * completed requests and responses: the deadline runs whenever they are equal (nothing in flight) and stops while the
 * server owes a response, so a slow handler is never cut off (the write timeout covers slow readers). One instance
 * per channel; all methods run on the channel's event loop.
 */
internal class RequestDeadlineHandler(
    private val deadlineSeconds: Long,
) : ChannelDuplexHandler() {
    private var requestsRead = 0L
    private var responsesWritten = 0L
    private var deadline: ScheduledFuture<*>? = null

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        if (ctx.channel().isActive) arm(ctx)
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        arm(ctx)
        ctx.fireChannelActive()
    }

    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        if (msg is LastHttpContent) {
            requestsRead++
            // The server may have answered before the body arrived (version reject, 404, 413, ...): then nothing is in
            // flight once the request completes, and the next request gets a fresh deadline (#68 SEC-19).
            if (requestsRead > responsesWritten) disarm() else arm(ctx)
        }
        ctx.fireChannelRead(msg)
    }

    override fun write(
        ctx: ChannelHandlerContext,
        msg: Any,
        promise: ChannelPromise,
    ) {
        // A 1xx interim response (100 Continue) is not the answer to a request, so it is not counted (#68 SEC-20).
        if (msg is LastHttpContent && !(msg is HttpResponse && msg.status().codeClass() == HttpStatusClass.INFORMATIONAL)) {
            val listener =
                ChannelFutureListener { future ->
                    if (future.isSuccess) {
                        responsesWritten++
                        if (responsesWritten >= requestsRead) arm(ctx)
                    }
                }
            ctx.write(msg, promise.unvoid().addListener(listener))
        } else {
            ctx.write(msg, promise)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        disarm()
        ctx.fireChannelInactive()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        disarm()
    }

    private fun arm(ctx: ChannelHandlerContext) {
        disarm()
        deadline = ctx.executor().schedule({ ctx.close() }, deadlineSeconds, TimeUnit.SECONDS)
    }

    private fun disarm() {
        deadline?.cancel(false)
        deadline = null
    }
}
