package com.limitart.net.binary.util;

import java.io.IOException;
import java.util.List;

import com.limitart.net.binary.listener.SendMessageListener;
import com.limitart.net.binary.message.Message;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

public class SendMessageUtil {
	public static void sendMessage(Channel channel, Message msg, SendMessageListener listener) throws Exception {
		if (channel == null) {
			if (listener != null) {
				listener.onComplete(false, new NullPointerException("channel"), channel);
			}
			return;
		}
		if (!channel.isWritable()) {
			if (listener != null) {
				listener.onComplete(false, new IOException(" channel " + channel.remoteAddress() + " is unwritable"),
						channel);
			}
			return;
		}
		ByteBuf buffer = channel.alloc().ioBuffer();
		buffer.writeShort(0);
		buffer.writeShort(msg.getMessageId());
		msg.buffer(buffer);
		msg.encode();
		msg.buffer(null);
		int sumLen = buffer.readableBytes() - 2;
		buffer.setShort(0, sumLen);
		ChannelFuture writeAndFlush = channel.writeAndFlush(buffer);
		writeAndFlush.addListener(new ChannelFutureListener() {

			@Override
			public void operationComplete(ChannelFuture arg0) throws Exception {
				if (listener != null) {
					listener.onComplete(arg0.isSuccess(), arg0.cause(), arg0.channel());
				}
			}
		});
	}

	public static void sendMessage(List<Channel> channels, Message msg, SendMessageListener listener) throws Exception {
		if (channels == null || channels.isEmpty()) {
			if (listener != null) {
				listener.onComplete(false, new IOException(" channel list  is null"), null);
			}
			return;
		}
		ByteBuf buffer = Unpooled.directBuffer(256);
		buffer.writeShort(0);
		buffer.writeShort(msg.getMessageId());
		msg.buffer(buffer);
		msg.encode();
		msg.buffer(null);
		int sumLen = buffer.readableBytes() - 2;
		// 替换长度的值
		buffer.setShort(0, sumLen);
		for (int i = 0; i < channels.size(); ++i) {
			Channel channel = channels.get(i);
			if (!channel.isWritable()) {
				if (listener != null) {
					listener.onComplete(false,
							new IOException(" channel " + channel.remoteAddress() + " is unwritable"), channel);
				}
				continue;
			}
			ChannelFuture writeAndFlush = channel.writeAndFlush(buffer.retainedSlice());
			if (listener != null) {
				writeAndFlush.addListener(new ChannelFutureListener() {

					@Override
					public void operationComplete(ChannelFuture arg0) throws Exception {
						listener.onComplete(arg0.isSuccess(), arg0.cause(), arg0.channel());
					}
				});
			}
			if (i == channels.size() - 1) {
				buffer.release();
			}
		}
	}
}
