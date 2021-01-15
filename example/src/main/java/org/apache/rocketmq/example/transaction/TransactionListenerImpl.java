/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.example.transaction;

import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TransactionListenerImpl implements TransactionListener {

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        LocalTransactionState state = LocalTransactionState.UNKNOW;

        try {
            String msgBody = new String(msg.getBody(), "utf-8");
            //执行本地业务的时候，再插入一条数据到事务表中，供checkLocalTransaction进行check使用，避免doBusinessCommit业务成功，但是未返回Commit
            boolean result = doBusinessCommit(msg.getKeys(), msgBody);
            if (result) {
                state = LocalTransactionState.COMMIT_MESSAGE;
            } else {
                state = LocalTransactionState.ROLLBACK_MESSAGE;
            }
        } catch (Exception e) {
            e.printStackTrace();
            state = LocalTransactionState.ROLLBACK_MESSAGE;
        }
        return state;
    }

    public boolean doBusinessCommit(String messageKey, String msgBody) {
        System.out.println("do something in DataBase");
        System.out.println("insert 事务消息到本地消息表中，消息执行成功，messageKey为：" + messageKey);
        return false;
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        LocalTransactionState state = LocalTransactionState.UNKNOW;
        try {
            Boolean result = checkBusinessStatus(msg.getTransactionId());
            if (result) {
                state = LocalTransactionState.COMMIT_MESSAGE;
            } else {
                state = LocalTransactionState.ROLLBACK_MESSAGE;
            }
        } catch (Exception e) {
            e.printStackTrace();
            state = LocalTransactionState.ROLLBACK_MESSAGE;
        }
        return state;
    }

    /**
     * 根据transactionId查询数据库如果数据库存在该条记录,messageKey为"+transactionId+"的消息已经消费成功了，可以提交消息
     *
     * @param transactionId
     * @return
     */
    public static Boolean checkBusinessStatus(String transactionId) {
        boolean result = false;
        // result=queryByMessageKey(transactionId);
        if (result) {
            return true;
        } else {
            return false;
        }
    }
}
