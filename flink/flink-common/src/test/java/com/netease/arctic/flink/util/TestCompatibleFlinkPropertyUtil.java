/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.flink.util;

import com.netease.arctic.flink.table.descriptors.ArcticValidator;
import org.apache.flink.configuration.Configuration;
import org.junit.Assert;
import org.junit.Test;

public class TestCompatibleFlinkPropertyUtil {
  @Test
  public void testGetNewProperty() {
    Configuration config = new Configuration();
    Assert.assertEquals(
        ArcticValidator.ARCTIC_LOG_CONSISTENCY_GUARANTEE_ENABLE.defaultValue(),
        CompatibleFlinkPropertyUtil.propertyAsBoolean(
            config, ArcticValidator.ARCTIC_LOG_CONSISTENCY_GUARANTEE_ENABLE));

    config.setBoolean(ArcticValidator.ARCTIC_LOG_CONSISTENCY_GUARANTEE_ENABLE, true);
    Assert.assertTrue(
        CompatibleFlinkPropertyUtil.propertyAsBoolean(
            config, ArcticValidator.ARCTIC_LOG_CONSISTENCY_GUARANTEE_ENABLE));

    config.setBoolean(ArcticValidator.ARCTIC_LOG_CONSISTENCY_GUARANTEE_ENABLE_LEGACY, false);
    Assert.assertTrue(
        CompatibleFlinkPropertyUtil.propertyAsBoolean(
            config, ArcticValidator.ARCTIC_LOG_CONSISTENCY_GUARANTEE_ENABLE));
  }

  @Test
  public void testGetLegacyProperty() {
    Configuration config = new Configuration();
    config.setBoolean(ArcticValidator.ARCTIC_LOG_CONSISTENCY_GUARANTEE_ENABLE_LEGACY, true);
    Assert.assertTrue(
        CompatibleFlinkPropertyUtil.propertyAsBoolean(
            config, ArcticValidator.ARCTIC_LOG_CONSISTENCY_GUARANTEE_ENABLE));
  }
}
