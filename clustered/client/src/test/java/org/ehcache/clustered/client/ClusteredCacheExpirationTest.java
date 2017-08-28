/*
 * Copyright Terracotta, Inc.
 *
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

package org.ehcache.clustered.client;

import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.CachePersistenceException;
import org.ehcache.PersistentCacheManager;
import org.ehcache.clustered.client.config.builders.ClusteredResourcePoolBuilder;
import org.ehcache.clustered.client.config.builders.ClusteredStoreConfigurationBuilder;
import org.ehcache.clustered.client.internal.UnitTestConnectionService;
import org.ehcache.clustered.common.Consistency;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.expiry.Duration;
import org.ehcache.expiry.Expirations;
import org.ehcache.impl.internal.TimeSourceConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ehcache.clustered.client.config.builders.ClusteringServiceConfigurationBuilder.cluster;
import static org.ehcache.config.builders.CacheConfigurationBuilder.newCacheConfigurationBuilder;
import static org.ehcache.config.builders.CacheManagerBuilder.newCacheManagerBuilder;

public class ClusteredCacheExpirationTest {

  private static final URI CLUSTER_URI = URI.create("terracotta://example.com:9540/my-application");
  private static final String CLUSTERED_CACHE = "clustered-cache";

  private TestTimeSource timeSource = new TestTimeSource();

  private CacheManagerBuilder<PersistentCacheManager> clusteredCacheManagerBuilder =
      newCacheManagerBuilder()
          .using(new TimeSourceConfiguration(timeSource))
          .with(cluster(CLUSTER_URI).autoCreate())
          .withCache(CLUSTERED_CACHE, newCacheConfigurationBuilder(Long.class, String.class,
              ResourcePoolsBuilder.newResourcePoolsBuilder()
                  .heap(10)
                  .with(ClusteredResourcePoolBuilder.clusteredDedicated("primary-server-resource", 32, MemoryUnit.MB)))
                .withExpiry(Expirations.timeToLiveExpiration(Duration.of(1, TimeUnit.SECONDS)))
              .add(ClusteredStoreConfigurationBuilder.withConsistency(Consistency.STRONG)));

  @Before
  public void definePassthroughServer() throws Exception {
    UnitTestConnectionService.add(CLUSTER_URI,
        new UnitTestConnectionService.PassthroughServerBuilder()
            .resource("primary-server-resource", 64, MemoryUnit.MB)
            .build());
  }

  @After
  public void removePassthroughServer() throws Exception {
    UnitTestConnectionService.remove(CLUSTER_URI);
  }

  @Test
  public void testGetExpirationPropagatedToHigherTiers() throws CachePersistenceException {
    try(PersistentCacheManager cacheManager = clusteredCacheManagerBuilder.build(true)) {
      Cache<Long, String> cache = cacheManager.getCache(CLUSTERED_CACHE, Long.class, String.class);
      cache.put(1L, "value"); // store on the cluster
      cache.get(1L); // push it up on heap tier
      timeSource.advanceTime(1500); // go after expiration
      assertThat(cache.get(1L)).isEqualTo(null); // the value should have expired
    }
  }

  @Test
  public void testPutIfAbsentExpirationPropagatedToHigherTiers() throws CachePersistenceException {
    try(PersistentCacheManager cacheManager = clusteredCacheManagerBuilder.build(true)) {
      Cache<Long, String> cache = cacheManager.getCache(CLUSTERED_CACHE, Long.class, String.class);
      cache.put(1L, "value"); // store on the cluster
      cache.putIfAbsent(1L, "newvalue"); // push it up on heap tier
      timeSource.advanceTime(1500); // go after expiration
      assertThat(cache.get(1L)).isEqualTo(null); // the value should have expired
    }
  }
}

