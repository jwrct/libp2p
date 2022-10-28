package org.tron.p2p.example.quic;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j(topic = "net")
public class QuicClient {
  private QuicClient() { }

  public static void main(String[] args) throws Exception {
    QuicSslContext context = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).
        applicationProtocols("h3").build();
    NioEventLoopGroup group = new NioEventLoopGroup(1);
    try {
      ChannelHandler codec = new QuicClientCodecBuilder()
          .sslContext(context)
          .maxIdleTimeout(50000, TimeUnit.MILLISECONDS)
          .initialMaxData(10000000)
          // As we don't want to support remote initiated streams just setup the limit for local initiated
          // streams in this example.
          .initialMaxStreamDataBidirectionalLocal(1000000)
          .build();

      Bootstrap bs = new Bootstrap();
      Channel channel = bs.group(group)
          .channel(NioDatagramChannel.class)
          .handler(codec)
          .bind(9999).sync().channel();

      QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
          .streamHandler(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
              // As we did not allow any remote initiated streams we will never see this method called.
              // That said just let us keep it here to demonstrate that this handle would be called
              // for each remote initiated stream.
              log.info("Channel active {}", ctx.channel().remoteAddress());
              //ctx.close();
            }
          })
          .remoteAddress(new InetSocketAddress("47.252.73.5", 8992))
          .connect()
          .get();

      QuicStreamChannel streamChannel = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
          new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
              log.info("rcv time {}", System.currentTimeMillis());
              ByteBuf byteBuf = (ByteBuf) msg;
              //System.err.println(byteBuf.toString(CharsetUtil.US_ASCII));
              log.info("rcv msg size {}", byteBuf.readableBytes());
              byteBuf.release();
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
              if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
                // Close the connection once the remote peer did send the FIN for this stream.
                log.info("QuicChannel closed {}", ctx.channel().remoteAddress());
                ((QuicChannel) ctx.channel().parent()).close(true, 0,
                    ctx.alloc().directBuffer(16)
                        .writeBytes(new byte[]{'k', 't', 'h', 'x', 'b', 'y', 'e'}));
              }
            }
          }).sync().getNow();
      // Write the data and send the FIN. After this its not possible anymore to write any more data.
      streamChannel.writeAndFlush(Unpooled.copiedBuffer("GET /\r\n", CharsetUtil.US_ASCII));
          //.addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);

      // Wait for the stream channel and quic channel to be closed (this will happen after we received the FIN).
      // After this is done we will close the underlying datagram channel.
      streamChannel.closeFuture().sync();
      quicChannel.closeFuture().sync();
      channel.close().sync();
    } finally {
      group.shutdownGracefully();
    }
  }
}
