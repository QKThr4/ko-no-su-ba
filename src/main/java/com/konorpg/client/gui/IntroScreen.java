package com.konorpg.client.gui;

import com.konorpg.client.ClientRpgData;
import com.konorpg.rpg.Perks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The goddess Aqua offers ONE permanent gift to take to the other world. */
public class IntroScreen extends Screen {
    private static final String SPEECH = "Ahn? Mais um morto... Você morreu de um jeito bem ridículo, sabia? Mas não se preocupe! "
            + "Eu sou Aqua, uma DEUSA, e posso mandar você para outro mundo. Pode levar UMA coisa comigo... quer dizer, com você. Escolha!";
    private String selected = "";
    private int top, descX, descW;

    public IntroScreen() { super(Component.literal("A Escolha")); }

    @Override
    protected void init() {
        int total = Math.min(width - 12, 520);
        int x0 = (width - total) / 2;
        List<FormattedCharSequence> lines = font.split(Component.literal(SPEECH), total - 12);
        top = 34 + lines.size() * 10;
        int leftW = Math.min(230, total / 2);
        descX = x0 + leftW + 10;
        descW = total - leftW - 10;
        int y = top;
        List<Perks.Perk> list = new ArrayList<>(Perks.ALL.values());
        for (Perks.Perk p : list) {
            addRenderableWidget(Button.builder(Component.literal((selected.equals(p.id()) ? "> " : "") + p.name()), b -> { selected = p.id(); rebuildWidgets(); })
                    .bounds(x0, y, leftW, 18).build());
            y += 20;
        }
        Button ok = Button.builder(Component.literal("CONFIRMAR ESCOLHA (permanente)"), b -> {
            CompoundTag t = new CompoundTag();
            t.putString("id", selected);
            ClientRpgData.send("choose_perk", t);
            minecraft.setScreen(null);
        }).bounds(x0, Math.min(height - 24, y + 8), leftW, 20).build();
        ok.active = !selected.isEmpty();
        addRenderableWidget(ok);
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) { }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xF0F4F8FF);
        g.fill(0, 0, width, height, 0x600A1A40);
        int total = Math.min(width - 12, 520), x0 = (width - total) / 2;
        Ui.text(g, "Aqua, a Deusa:", x0, 8, 0xFF8FD6FF);
        int ly = 22;
        for (FormattedCharSequence seq : font.split(Component.literal(SPEECH), total - 12)) {
            g.drawString(font, seq, x0, ly, 0xFFFFFFFF, false);
            ly += 10;
        }
        super.render(g, mx, my, pt);
        Ui.panel(g, descX, top, descW, Math.max(80, Perks.ALL.size() * 20 - 2));
        if (selected.isEmpty()) {
            Ui.text(g, "Selecione um presente à esquerda.", descX + 8, top + 8, Ui.DIM);
        } else {
            Perks.Perk p = Perks.ALL.get(selected);
            Ui.text(g, p.name(), descX + 8, top + 8, Ui.GOLD);
            int dy = top + 24;
            for (FormattedCharSequence seq : font.split(Component.literal(p.desc()), descW - 16)) {
                g.drawString(font, seq, descX + 8, dy, Ui.TEXT, false);
                dy += 10;
            }
            Ui.text(g, "A escolha é permanente.", descX + 8, top + Math.max(80, Perks.ALL.size() * 20 - 2) - 14, Ui.DIM);
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public boolean shouldCloseOnEsc() { return false; }
}
