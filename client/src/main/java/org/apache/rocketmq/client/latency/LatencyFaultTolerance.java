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

package org.apache.rocketmq.client.latency;

/**
 * 延迟机制接口规范
 * @param <T></T>
 * @return 
 * @author chenqi
 * @date 2020/12/23 20:04
*/
public interface LatencyFaultTolerance<T> {
    /**
     * 更新失败条目
     * @param name
     * @param currentLatency
     * @param notAvailableDuration
     * @return void
     * @author chenqi
     * @date 2020/12/23 20:06
    */
    void updateFaultItem(final T name, final long currentLatency, final long notAvailableDuration);
    
    /**
     * 判断broker是否可用
     * @param name
     * @return boolean
     * @author chenqi
     * @date 2020/12/23 20:06
    */
    boolean isAvailable(final T name);

    /**
     * 移除fault条目,意味着broker重新参与路由计算
     * @param name
     * @return void
     * @author chenqi
     * @date 2020/12/23 20:10
    */
    void remove(final T name);
    
    /**
     * 尝试从规避的broker中选择一个可用的broker,如果没有找到,将返回null
     * @return T
     * @author chenqi
     * @date 2020/12/23 20:07
    */
    T pickOneAtLeast();
}
