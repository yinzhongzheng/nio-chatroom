package com.yzz.nio.chatroom.client;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * describe:
 * E-mail:yzzstyle@163.com  date:2019/1/15
 *
 * @Since 0.0.1
 */
public class ChatRoomClient {

    private Charset charset = Charset.forName("UTF-8");
    private SocketChannel socketChannel;
    private Scanner scanner = new Scanner(System.in);
    private ByteBuffer byteBuffer = ByteBuffer.allocateDirect(128);
    private Logger log = Logger.getLogger(ChatRoomClient.class);
    private ExecutorService pool = Executors.newFixedThreadPool(2);
    private volatile boolean stop;

    private ChatRoomClient(String host, int port) throws IOException {
        SocketAddress socketAddress = new InetSocketAddress(host, port);
        socketChannel = SocketChannel.open(socketAddress);
        log.info("client has connected remote " + host + ":" + port);
    }


    public void write() {
        try {
            while (!stop) {
                String msg = scanner.next();
                ByteBuffer b = charset.encode(msg);
                socketChannel.write(b);
                scanner.reset();
            }
        } catch (IOException e) {
            e.printStackTrace();
            clear();
        }

    }

    public void read() {
        try {
            while (!stop) {
                //阻塞调用后，position=message.length limit = 128
                socketChannel.read(byteBuffer);
                //调用后，此时position = 0,limit = message.length
                byteBuffer.flip();
                //调用后，此时position=message.length,limit=128
                byte tag = byteBuffer.get();
                //此时需要将position的位置右移一位
                byteBuffer.position(1);
                CharBuffer charBuffer = charset.decode(byteBuffer);
                if (tag == 1) {
                    System.out.println(charBuffer + "(本人)");
                } else {
                    System.out.println(charBuffer);
                }
                byteBuffer.clear();
            }
        } catch (IOException e) {
            e.printStackTrace();
            clear();
        }

    }

    /**
     * 释放资源
     */
    private void clear() {
        try {
            stop = true;
            if (null != socketChannel) socketChannel.close();
            if (scanner != null) scanner.close();
            if (byteBuffer != null) byteBuffer.clear();
            if (pool != null && !pool.isShutdown()) pool.shutdownNow();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 启动，通过两个线程去服务读写
     *
     * @param host
     * @param port
     */
    public static void start(String host, int port) {
        try {
            ChatRoomClient chatRoomClient = new ChatRoomClient(host, port);
            chatRoomClient.pool.execute(() -> {
                chatRoomClient.read();
            });
            chatRoomClient.pool.execute(() -> {
                chatRoomClient.write();
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
