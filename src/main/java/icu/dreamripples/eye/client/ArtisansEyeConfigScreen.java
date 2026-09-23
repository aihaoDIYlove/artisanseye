package icu.dreamripples.eye.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 扁平单页配置屏（模组列表"配置"按钮的入口，经 IConfigScreenFactory 注册）。
 * 目前两项：自动锻造开关（原样式 开/关 按钮）+ 敲击间隔滑条（2–20 刻），
 * 不做分类/重置等多余结构。改动即时生效，落盘见各写入点注释。
 */
public class ArtisansEyeConfigScreen extends Screen
{
    private static final int CONTROL_WIDTH = 200;

    private final Screen parent;

    public ArtisansEyeConfigScreen(Screen parent)
    {
        super(Component.translatable("artisanseye.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        final int x = this.width / 2 - CONTROL_WIDTH / 2;

        addRenderableWidget(CycleButton.onOffBuilder(ArtisansEyeConfig.AUTO_FORGE.get())
            .create(x, this.height / 2 - 28, CONTROL_WIDTH, 20,
                Component.translatable("artisanseye.config.auto_forge"),
                (button, value) -> setAutoForge(value)));

        final PressIntervalSlider slider = new PressIntervalSlider(x, this.height / 2 + 12, CONTROL_WIDTH, 20,
            ArtisansEyeConfig.pressInterval());
        slider.setTooltip(Tooltip.create(Component.translatable("artisanseye.config.press_interval.desc")));
        addRenderableWidget(slider);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(x, this.height - 28, CONTROL_WIDTH, 20)
            .build());
    }

    /** ModConfigSpec#set 只写内存不落盘，必须手动 save() */
    private static void setAutoForge(boolean value)
    {
        ArtisansEyeConfig.AUTO_FORGE.set(value);
        ArtisansEyeConfig.SPEC.save();
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick)
    {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        gui.drawCenteredString(this.font,
            Component.translatable("artisanseye.config.auto_forge.desc"),
            this.width / 2, this.height / 2 - 4, 0xA0A0A0);
    }

    @Override
    public void onClose()
    {
        if (this.minecraft != null)
        {
            this.minecraft.setScreen(parent); // 回到模组列表
        }
        else
        {
            super.onClose();
        }
    }

    /**
     * 敲击间隔滑条（2–20 刻）。拖动中 applyValue 逐步触发，只更新内存值即时生效；
     * 落盘在松手时统一做一次，避免拖一下写一次盘。
     */
    private static class PressIntervalSlider extends AbstractSliderButton
    {
        private static final int MIN_TICKS = 2;
        private static final int MAX_TICKS = 20;

        PressIntervalSlider(int x, int y, int width, int height, int current)
        {
            super(x, y, width, height, Component.empty(),
                (current - MIN_TICKS) / (double) (MAX_TICKS - MIN_TICKS));
            updateMessage(); // 父类构造器不会调，标签要在这里初始化
        }

        private int valueTicks()
        {
            return MIN_TICKS + (int) Math.round(this.value * (MAX_TICKS - MIN_TICKS));
        }

        @Override
        protected void updateMessage()
        {
            setMessage(Component.translatable("artisanseye.config.press_interval.value", valueTicks()));
        }

        @Override
        protected void applyValue()
        {
            ArtisansEyeConfig.setPressInterval(valueTicks());
        }

        @Override
        public void onRelease(double mouseX, double mouseY)
        {
            super.onRelease(mouseX, mouseY);
            ArtisansEyeConfig.SPEC.save();
        }
    }
}
