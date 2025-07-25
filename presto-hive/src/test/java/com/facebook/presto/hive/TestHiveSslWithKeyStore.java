/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.net.URISyntaxException;
import java.nio.file.Paths;

// TODO: these tests are disabled because they rely on an expired certificate
@Test(enabled = false)
public class TestHiveSslWithKeyStore
        extends AbstractHiveSslTest
{
    TestHiveSslWithKeyStore() throws URISyntaxException
    {
        super(ImmutableMap.<String, String>builder()
                // This is required when connecting to ssl enabled hms
                .put("hive.metastore.thrift.client.tls.enabled", "true")
                .put("hive.metastore.thrift.client.tls.keystore-path", Paths.get((TestHiveSslWithKeyStore.class.getResource("/hive_ssl_enable/hive-metastore.jks")).toURI()).toFile().toString())
                .put("hive.metastore.thrift.client.tls.keystore-password", "123456")
                .build());
    }
}
