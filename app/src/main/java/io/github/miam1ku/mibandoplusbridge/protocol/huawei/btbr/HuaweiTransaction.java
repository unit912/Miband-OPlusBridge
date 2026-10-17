// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一组按序执行的动作；任一动作失败则丢弃其后所有动作。
 *
 * <p>对应 Gadgetbridge 的 {@code Transaction}（原实现继承 {@code AbstractTransaction}，
 * 而父类只提供 {@code toString()} 与任务名，属于纯样板，故合并到本类）。
 */
public class HuaweiTransaction {

    private final String taskName;
    private final List<HuaweiSocketAction> actions = new ArrayList<>(4);
    private HuaweiSocketCallback callback;

    public HuaweiTransaction(String taskName) {
        this.taskName = taskName;
    }

    public void add(HuaweiSocketAction action) {
        actions.add(action);
    }

    public List<HuaweiSocketAction> getActions() {
        return Collections.unmodifiableList(actions);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    public String getTaskName() {
        return taskName;
    }

    /** 事务执行期间要接收的回调，可为 {@code null}。 */
    public void setCallback(HuaweiSocketCallback callback) {
        this.callback = callback;
    }

    public HuaweiSocketCallback getCallback() {
        return callback;
    }

    public int getActionCount() {
        return actions.size();
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US,
                "Transaction task: %s with %d actions", taskName, actions.size());
    }
}