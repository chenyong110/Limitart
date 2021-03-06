package com.limitart.net.binary.client;

import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.limitart.net.binary.client.config.BinaryClientConfig;
import com.limitart.net.binary.client.listener.BinaryClientEventListener;
import com.limitart.net.binary.codec.ByteDecoder;
import com.limitart.net.binary.handler.IHandler;
import com.limitart.net.binary.message.Message;
import com.limitart.net.binary.message.MessageFactory;
import com.limitart.net.binary.message.constant.InnerMessageEnum;
import com.limitart.net.binary.message.impl.validate.ConnectionValidateClientMessage;
import com.limitart.net.binary.message.impl.validate.ConnectionValidateServerMessage;
import com.limitart.net.binary.message.impl.validate.ConnectionValidateSuccessServerMessage;
import com.limitart.net.binary.util.SendMessageUtil;
import com.limitart.util.SymmetricEncryptionUtil;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * 二进制通信客户端
 * 
 * @author hank
 *
 */
@Sharable
public class BinaryClient extends ChannelInboundHandlerAdapter {
	private static Logger log = LogManager.getLogger();
	private BinaryClientEventListener clientEventListener;
	private MessageFactory messageFactory;
	private BinaryClientConfig clientConfig;
	private static EventLoopGroup group;
	private Bootstrap bootstrap;
	private Channel channel;
	private SymmetricEncryptionUtil decodeUtil;
	static {
		if (Epoll.isAvailable()) {
			group = new EpollEventLoopGroup();
		} else {
			group = new NioEventLoopGroup();
		}
	}

	public BinaryClient(BinaryClientConfig config, BinaryClientEventListener clientEventListener,
			MessageFactory messageFactory) throws Exception {
		if (config == null) {
			throw new NullPointerException("BinaryClientConfig");
		}
		if (clientEventListener == null) {
			throw new NullPointerException("BinaryClientEventListener");
		}
		if (messageFactory == null) {
			throw new NullPointerException("MessageFactory");
		}
		this.clientConfig = config;
		this.clientEventListener = clientEventListener;
		// 内部消息注册
		this.messageFactory = messageFactory
				.registerMsg(InnerMessageEnum.ConnectionValidateServerMessage.getValue(),
						ConnectionValidateServerMessage.class, new ConnectionValidateServerHandler())
				.registerMsg(InnerMessageEnum.ConnectionValidateSuccessServerMessage.getValue(),
						ConnectionValidateSuccessServerMessage.class, new ConnectionValidateSuccessServerHandler());
		decodeUtil = SymmetricEncryptionUtil.getDecodeInstance(clientConfig.getConnectionPass());
		bootstrap = new Bootstrap();
		if (Epoll.isAvailable()) {
			bootstrap.channel(EpollServerSocketChannel.class);
		} else {
			bootstrap.channel(NioSocketChannel.class);
			log.info(clientConfig.getClientName() + " nio init");
		}

		bootstrap.group(group).option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
				.handler(new ChannelInitializerImpl(this));
	}

	private class ChannelInitializerImpl extends ChannelInitializer<SocketChannel> {
		private BinaryClient client;

		private ChannelInitializerImpl(BinaryClient client) {
			this.client = client;
		}

		@Override
		protected void initChannel(SocketChannel ch) throws Exception {
			ch.pipeline().addLast(new ByteDecoder(this.client.clientConfig.getDataMaxLength()));
			ch.pipeline().addLast(this.client);
		}

	}

	public void schedule(Runnable command, long delay, TimeUnit unit) {
		group.schedule(command, delay, unit);
	}

	public void schedule(Runnable command, long delay, long period, TimeUnit unit) {
		group.scheduleAtFixedRate(command, delay, period, unit);
	}

	public BinaryClient disConnect() {
		group.shutdownGracefully();
		if (channel != null) {
			channel.close();
			channel = null;
		}
		return this;
	}

	public BinaryClient connect() {
		tryReconnect(0);
		return this;
	}

	private void connect0() {
		if (channel != null && channel.isWritable()) {
			return;
		}
		log.info(clientConfig.getClientName() + " start connect server：" + this.clientConfig.getRemoteIp() + ":"
				+ this.clientConfig.getRemotePort() + "...");
		try {
			bootstrap.connect(this.clientConfig.getRemoteIp(), this.clientConfig.getRemotePort())
					.addListener(new ChannelFutureListener() {

						@Override
						public void operationComplete(ChannelFuture channelFuture) throws Exception {
							channel = channelFuture.channel();
							if (channelFuture.isSuccess()) {
								log.info(clientConfig.getClientName() + " connect server："
										+ BinaryClient.this.clientConfig.getRemoteIp() + ":"
										+ BinaryClient.this.clientConfig.getRemotePort() + " success！");
							} else {
								log.error(clientConfig.getClientName() + " try connect server："
										+ clientConfig.getRemoteIp() + ":" + clientConfig.getRemotePort() + " fail",
										channelFuture.cause().getMessage());
								if (clientConfig.getAutoReconnect() > 0) {
									tryReconnect(clientConfig.getAutoReconnect());
								}
							}
						}
					}).sync();
		} catch (Exception e) {
			log.error(e, e);
		}
	}

	private void tryReconnect(int waitSeconds) {
		if (channel != null) {
			channel.close();
			channel = null;
		}
		schedule(new Runnable() {

			@Override
			public void run() {
				connect0();
			}
		}, waitSeconds, TimeUnit.SECONDS);
	}

	private void decodeConnectionValidateData(String validateStr) {
		try {
			String decode = decodeUtil.decode(validateStr);
			int validateRandom = Integer.parseInt(decode);
			ConnectionValidateClientMessage msg = new ConnectionValidateClientMessage();
			msg.setValidateRandom(validateRandom);
			SendMessageUtil.sendMessage(channel, msg, null);
			log.info(clientConfig.getClientName() + " parse validate code success，return result：" + validateRandom);
		} catch (Exception e) {
			log.error(e, e);
		}
	}

	private void onConnectionValidateSeccuss(String remote) {
		log.info("server validate success,remote:" + remote);
		this.clientEventListener.onConnectionEffective(this);
	}

	public String channelLongID() {
		return this.channel.id().asLongText();
	}

	public Channel channel() {
		return this.channel;
	}

	public SocketAddress remoteAddress() {
		return this.channel.remoteAddress();
	}

	public MessageFactory getMessageFactory() {
		return messageFactory;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object arg) throws Exception {
		ByteBuf buffer = (ByteBuf) arg;
		try {
			// 消息id
			short messageId = buffer.readShort();
			Message msg = BinaryClient.this.messageFactory.getMessage(messageId);
			if (msg == null) {
				throw new Exception(clientConfig.getClientName() + " message empty,id:" + messageId);
			}
			msg.buffer(buffer);
			msg.decode();
			IHandler handler = BinaryClient.this.messageFactory.getHandler(messageId);
			if (handler == null) {
				throw new Exception(clientConfig.getClientName() + " can not find handler for message,id:" + messageId);
			}
			msg.setHandler(handler);
			msg.setChannel(ctx.channel());
			msg.setClient(this);
			// 如果是内部消息，则自己消化
			if (InnerMessageEnum.getTypeByValue(messageId) != null) {
				handler.handle(msg);
			} else {
				this.clientEventListener.dispatchMessage(msg);
			}
		} finally {
			buffer.release();
		}
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		this.clientEventListener.onChannelActive(this);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		this.clientEventListener.onChannelInactive(this);
		if (clientConfig.getAutoReconnect() > 0) {
			tryReconnect(clientConfig.getAutoReconnect());
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		this.clientEventListener.onExceptionCaught(this, cause);
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
		this.clientEventListener.onChannelRegistered(this);
	}

	@Override
	public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
		this.clientEventListener.onChannelUnregistered(this);
	}

	private class ConnectionValidateServerHandler implements IHandler {

		@Override
		public void handle(Message message) {
			ConnectionValidateServerMessage msg = (ConnectionValidateServerMessage) message;
			String validateStr = msg.getValidateStr();
			msg.getClient().decodeConnectionValidateData(validateStr);
		}

	}

	private class ConnectionValidateSuccessServerHandler implements IHandler {

		@Override
		public void handle(Message message) {
			message.getClient().onConnectionValidateSeccuss(message.getChannel().remoteAddress().toString());
		}
	}
}
