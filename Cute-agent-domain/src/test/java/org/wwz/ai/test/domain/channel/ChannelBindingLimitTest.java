package org.wwz.ai.test.domain.channel;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 绑定上限回归：并发落库时 countAll + insert 会同时看到「未达上限」，
 * insertIfBelowLimit 必须原子地完成「校验 + 插入」。
 */
public class ChannelBindingLimitTest {

    @Test
    public void concurrentInsertsNeverExceedLimit() throws Exception {
        InMemoryBindingRepository repository = new InMemoryBindingRepository();
        int limit = 5;
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger inserted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.execute(() -> {
                try {
                    start.await();
                    ChannelBinding binding = ChannelBinding.builder()
                            .ownerUserId("owner-" + index)
                            .platform(ChannelBinding.PLATFORM_QQBOT)
                            .build();
                    if (repository.insertIfBelowLimit(binding, limit)) {
                        inserted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        Assert.assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        Assert.assertEquals(limit, inserted.get());
        Assert.assertEquals(limit, repository.countAll());
    }

    private static final class InMemoryBindingRepository implements ChannelBindingRepository {
        private final List<ChannelBinding> rows = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public Optional<ChannelBinding> findById(Long bindingId) {
            return rows.stream().filter(row -> bindingId != null && bindingId.equals(row.getId())).findFirst();
        }

        @Override
        public Optional<ChannelBinding> findByOwner(String ownerUserId, String platform) {
            return rows.stream()
                    .filter(row -> java.util.Objects.equals(ownerUserId, row.getOwnerUserId())
                            && java.util.Objects.equals(platform, row.getPlatform()))
                    .findFirst();
        }

        @Override
        public Optional<ChannelBinding> findByAppId(String platform, String appId) {
            return rows.stream()
                    .filter(row -> java.util.Objects.equals(appId, row.getAppId())
                            && java.util.Objects.equals(platform, row.getPlatform()))
                    .findFirst();
        }

        @Override
        public List<ChannelBinding> listByStatus(String status, int limit) {
            return rows.stream().filter(row -> java.util.Objects.equals(status, row.getStatus())).toList();
        }

        @Override
        public long countAll() {
            return rows.size();
        }

        @Override
        public void insert(ChannelBinding binding) {
            binding.setId((long) rows.size() + 1);
            rows.add(binding);
        }

        @Override
        public void updateStatus(Long bindingId, String status, String lastError,
                                 java.time.LocalDateTime connectedAt) {
        }

        @Override
        public void disable(Long bindingId) {
        }
    }
}
