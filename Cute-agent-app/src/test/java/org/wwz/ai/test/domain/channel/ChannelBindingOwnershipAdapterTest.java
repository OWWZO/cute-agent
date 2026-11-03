package org.wwz.ai.test.domain.channel;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;
import org.wwz.ai.infrastructure.channel.ChannelBindingOwnershipAdapter;

import java.util.Optional;

/**
 * 渠道绑定授权回归：修复前只比较 ownerUserId，已解绑/删除的 binding 仍会放行。
 */
public class ChannelBindingOwnershipAdapterTest {

    private final ChannelBindingRepository repository = Mockito.mock(ChannelBindingRepository.class);
    private final ChannelBindingOwnershipAdapter adapter = new ChannelBindingOwnershipAdapter(repository);

    @Test
    public void activeBindingAllowsOwnerOnly() {
        Mockito.when(repository.findById(1L)).thenReturn(Optional.of(binding(ChannelBinding.STATUS_ACTIVE, 0)));

        Assert.assertTrue(adapter.isOwner(1L, "user-1"));
        Assert.assertFalse("非 owner 不得放行", adapter.isOwner(1L, "user-2"));
    }

    @Test
    public void disabledOrDeletedBindingIsRejected() {
        Mockito.when(repository.findById(2L)).thenReturn(Optional.of(binding(ChannelBinding.STATUS_DISABLED, 0)));
        Mockito.when(repository.findById(3L)).thenReturn(Optional.of(binding(ChannelBinding.STATUS_ACTIVE, 1)));

        Assert.assertFalse("已解绑的 binding 必须拒绝", adapter.isOwner(2L, "user-1"));
        Assert.assertFalse("已删除的 binding 必须拒绝", adapter.isOwner(3L, "user-1"));
    }

    @Test
    public void nullInputsFailClosed() {
        Assert.assertFalse(adapter.isOwner(null, "user-1"));
        Assert.assertFalse(adapter.isOwner(1L, null));
        Assert.assertFalse(adapter.isOwner(1L, "   "));
        Assert.assertFalse("不存在的 binding 必须拒绝", adapter.isOwner(404L, "user-1"));
    }

    private ChannelBinding binding(String status, int deleted) {
        return ChannelBinding.builder()
                .id(1L)
                .ownerUserId("user-1")
                .platform(ChannelBinding.PLATFORM_QQBOT)
                .status(status)
                .deleted(deleted)
                .build();
    }
}
