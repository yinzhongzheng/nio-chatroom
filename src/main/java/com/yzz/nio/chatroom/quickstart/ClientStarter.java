package com.yzz.nio.chatroom.quickstart;

import com.yzz.nio.chatroom.client.ChatRoomClient;

import java.io.IOException;

/**
 * describe:
 * E-mail:yzzstyle@163.com  date:2019/1/15
 *
 * @Since 0.0.1
 */
public class ClientStarter {

    public static void main(String[] args) {
        ChatRoomClient.start("127.0.0.1", 6633);
    }
}
