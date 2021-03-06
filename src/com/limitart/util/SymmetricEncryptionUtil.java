package com.limitart.util;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.netty.util.CharsetUtil;

/**
 * 对称加密验证工具
 * 
 * @author hank
 *
 */
public class SymmetricEncryptionUtil {
	private static final String TRANSFORMATION = "AES/CBC/NoPadding";
	private static final String ALGORITHM = "AES";
	private Key generateKey;
	private byte[] iv;

	public synchronized static SymmetricEncryptionUtil getEncodeInstance(String password, String ivStr)
			throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
			InvalidAlgorithmParameterException {
		return new SymmetricEncryptionUtil(password, ivStr);
	}

	public synchronized static SymmetricEncryptionUtil getDecodeInstance(String password)
			throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
			InvalidAlgorithmParameterException {
		return new SymmetricEncryptionUtil(password);
	}

	/**
	 * 加密端构造函数
	 * 
	 * @param password
	 * @param ivStr
	 * @throws NoSuchPaddingException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidAlgorithmParameterException
	 * @throws InvalidKeyException
	 */
	private SymmetricEncryptionUtil(String password, String ivStr) throws NoSuchAlgorithmException,
			NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException {
		byte[] bytes = password.getBytes(CharsetUtil.UTF_8);
		byte[] resultKey = new byte[16];
		System.arraycopy(bytes, 0, resultKey, 0, Math.min(resultKey.length, bytes.length));
		generateKey = new SecretKeySpec(resultKey, ALGORITHM);
		if (ivStr != null) {
			// 初始化16位向量
			this.iv = new byte[16];
			byte[] ivRaw = ivStr.getBytes(CharsetUtil.UTF_8);
			System.arraycopy(ivRaw, 0, this.iv, 0, Math.min(this.iv.length, ivRaw.length));
		}
	}

	/**
	 * 解密端构造函数
	 * 
	 * @param password
	 * @throws NoSuchPaddingException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidAlgorithmParameterException
	 * @throws InvalidKeyException
	 */
	private SymmetricEncryptionUtil(String password) throws NoSuchAlgorithmException, NoSuchPaddingException,
			InvalidKeyException, InvalidAlgorithmParameterException {
		this(password, null);
	}

	public String encode(byte[] bytes) throws Exception {
		// 加密验证
		int zeroFlag = 0;
		byte[] jsonByteFillZero = null;
		int len = bytes.length;
		if (len % 16 != 0) {
			len = (len / 16 + 1) * 16;
			zeroFlag = len - bytes.length;
			jsonByteFillZero = new byte[len];
			System.arraycopy(bytes, 0, jsonByteFillZero, 0, bytes.length);
		} else {
			jsonByteFillZero = bytes;
		}
		byte[] doFinal = null;
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(Cipher.ENCRYPT_MODE, generateKey, new IvParameterSpec(this.iv));
		doFinal = cipher.doFinal(jsonByteFillZero);
		byte[] content = new byte[doFinal.length + this.iv.length];
		System.arraycopy(this.iv, 0, content, 0, this.iv.length);
		System.arraycopy(doFinal, 0, content, this.iv.length, doFinal.length);
		byte[] base64Encode = SecurityUtil.base64Encode(content);
		String b64Str = new String(base64Encode, CharsetUtil.UTF_8);
		String result = b64Str.replace('+', '-').replace('/', '_').replace('=', '.');
		if (zeroFlag > 0) {
			result = "$" + String.format("%02d", zeroFlag) + result;
		}
		return result;
	}

	public String encode(String source) throws Exception {
		return encode(source.getBytes(CharsetUtil.UTF_8));
	}

	public String decode(String tokenSource) throws Exception {
		int zeroFlag = 0;
		if (tokenSource.startsWith("$")) {
			zeroFlag = Integer.parseInt(tokenSource.substring(1, 3));
			tokenSource = tokenSource.substring(3);
		}
		String token = tokenSource.replace('-', '+').replace('_', '/').replace('.', '=');
		byte[] base64Decode = SecurityUtil.base64Decode(token.getBytes(CharsetUtil.UTF_8));
		byte[] iv = new byte[16];
		byte[] content = new byte[base64Decode.length - iv.length];
		System.arraycopy(base64Decode, 0, iv, 0, iv.length);
		System.arraycopy(base64Decode, iv.length, content, 0, content.length);
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(Cipher.DECRYPT_MODE, generateKey, new IvParameterSpec(iv));
		byte[] doFinal = cipher.doFinal(content);
		// 去补零
		int realSize = doFinal.length;
		if (zeroFlag > 0) {
			realSize = doFinal.length - zeroFlag;
			byte[] afterZero = new byte[realSize];
			System.arraycopy(doFinal, 0, afterZero, 0, afterZero.length);
			return new String(afterZero, CharsetUtil.UTF_8);
		} else {
			return new String(doFinal, CharsetUtil.UTF_8);
		}
	}
}
