// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import io.github.miam1ku.mibandoplusbridge.data.WatchfaceBodies;
import io.github.miam1ku.mibandoplusbridge.data.WatchfacePreviewRenderer;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 表盘机身切换。
 *
 * <p>原本挂在「协议测试」里，但它其实是日常会用的外观设置，不是协议调试项 ——
 * 现在独立成页，从首页「常用 → 表盘机身切换」进入。
 */
public final class WatchfaceBodyActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeScreen screen;
    private TextView result;
    private final List<MaterialButton> bodyButtons = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = BridgeScreen.attach(this, "表盘机身", true);
        LinearLayout bodyCard = screen.card();
        screen.overline(bodyCard, "表盘机身");
        screen.caption(bodyCard,
                "设备空间和健康里那块表的外壳。点选后约半分钟内自动重画，设备页下次刷新即换。");
        screen.setLastChildMargin(bodyCard, 0);
        LinearLayout bodyGrid = new LinearLayout(this);
        bodyGrid.setOrientation(LinearLayout.VERTICAL);
        bodyCard.addView(bodyGrid);
        for (int i = 0; i < WatchfaceBodies.count(); i++) {
            final String key = WatchfaceBodies.key(i);
            MaterialButton button = screen.outlined(bodyGrid, "", () -> pickBody(key));
            bodyButtons.add(button);
        }
        refreshBodyButtons();
        result = screen.bodyText("等待操作。");
    }

    private void refreshBodyButtons() {
        String current = WatchfaceBodies.selectedKey(this);
        for (int i = 0; i < WatchfaceBodies.count(); i++) {
            String key = WatchfaceBodies.key(i);
            String label = key.equals(current)
                    ? "✓ " + WatchfaceBodies.name(i)
                    : WatchfaceBodies.name(i);
            bodyButtonFor(i).setText(label);
        }
    }

    private MaterialButton bodyButtonFor(int index) {
        return bodyButtons.get(index);
    }

    private void pickBody(String key) {
        if (!WatchfaceBodies.select(this, key)) {
            setResultText("机身切换失败：未知选项。");
            return;
        }
        refreshBodyButtons();
        setResultText("已选「" + WatchfaceBodies.nameOf(key) + "」，正在重画表盘…");
        worker.execute(() -> {
            String message;
            try {
                WatchfacePreviewRenderer.render(this);
                getContentResolver().notifyChange(
                        io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider.URI, null);
                message = "已切换为「" + WatchfaceBodies.nameOf(key) + "」。设备空间/健康页刷新后生效。";
            } catch (RuntimeException failure) {
                message = "重画失败：" + failure.getClass().getSimpleName();
            }
            String text = message;
            main.post(() -> {
                if (isDestroyed()) return;
                setResultText(text);
            });
        });
    }

    private void setResultText(String value) {
        if (result != null) result.setText(value);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
