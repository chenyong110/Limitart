package com.limitart.net.binary.message;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.limitart.net.binary.handler.IHandler;
import com.limitart.net.binary.message.define.IMessagePool;
import com.limitart.reflectasm.ConstructorAccess;
import com.limitart.util.ReflectionUtil;

import io.netty.util.collection.ShortObjectHashMap;

/**
 * 消息工厂 注意：这里的handler是单例，一定不能往里存成员变量
 * 
 * @author hank
 *
 */
public class MessageFactory {
	private static Logger log = LogManager.getLogger();
	// !!这里的asm应用经测试在JAVA8下最优
	private final ShortObjectHashMap<ConstructorAccess<? extends Message>> msgs = new ShortObjectHashMap<>();
	private final ShortObjectHashMap<IHandler> handlers = new ShortObjectHashMap<>();

	/**
	 * 通过反射调用具有IMessagePool接口的消息构造
	 * 
	 * @param packageName
	 * @return
	 * @throws Exception
	 * @see {@link IMessagePool}
	 */
	public static MessageFactory createByPackage(String packageName) throws Exception {
		MessageFactory messageFactory = new MessageFactory();
		List<Class<?>> classesByPackage = ReflectionUtil.getClassesByPackage(packageName, IMessagePool.class);
		for (Class<?> clzz : classesByPackage) {
			IMessagePool newInstance = (IMessagePool) clzz.newInstance();
			log.info("start register message pool：" + clzz.getSimpleName());
			newInstance.register(messageFactory);
		}
		return messageFactory;
	}

	public synchronized MessageFactory registerMsg(short id, Class<? extends Message> msgClass, IHandler handler) {
		if (msgs.containsKey(id)) {
			Class<? extends Message> class1 = msgs.get(id).newInstance().getClass();
			if (!class1.getName().equals(msgClass.getName())) {
				throw new IllegalArgumentException("message id duplicated:" + id + ",class old:" + class1.getName()
						+ ",class new:" + msgClass.getName());
			} else {
				return this;
			}
		}
		if (handlers.containsKey(id)) {
			throw new IllegalArgumentException("handler id duplicated:" + id);
		}
		ConstructorAccess<? extends Message> constructorAccess = ConstructorAccess.get(msgClass);
		msgs.put(id, constructorAccess);
		handlers.put(id, handler);
		log.debug("regist msg: {}，handler:{}", msgClass.getSimpleName(), handler.getClass().getSimpleName());
		return this;
	}

	public MessageFactory registerMsg(short id, Class<? extends Message> msgClass,
			Class<? extends IHandler> handlerClass) throws InstantiationException, IllegalAccessException {
		return registerMsg(id, msgClass, handlerClass.newInstance());
	}

	public Message getMessage(short msgId) throws InstantiationException, IllegalAccessException {
		if (!msgs.containsKey(msgId)) {
			return null;
		}
		ConstructorAccess<? extends Message> constructorAccess = msgs.get(msgId);
		return constructorAccess.newInstance();
	}

	public IHandler getHandler(short msgId) throws InstantiationException, IllegalAccessException {
		return handlers.get(msgId);
	}

}
