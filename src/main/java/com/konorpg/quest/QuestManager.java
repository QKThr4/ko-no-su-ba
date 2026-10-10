package com.konorpg.quest;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.konorpg.KonoRPG;
import com.konorpg.Config;
import com.konorpg.data.PlayerRpgData;
import com.konorpg.data.QuestProgress;
import com.konorpg.data.WorldRpgData;
import com.konorpg.network.Net;
import com.konorpg.rpg.CompanionManager;
import com.konorpg.rpg.Requirements;
import com.konorpg.rpg.RpgManager;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Loads quests from JSON and runs accept / progress / claim. EM BREVE: escort, deliver and spawn-on-accept encounters. */
public final class QuestManager extends SimpleJsonResourceReloadListener {
    public static final QuestManager INSTANCE = new QuestManager();
    private static final Map<String, Quest> QUESTS = new LinkedHashMap<>();
    private static final int MAX_ACTIVE = 8;

    private QuestManager() { super(new Gson(), "konorpg_quests"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller pf) {
        QUESTS.clear();
        for (var e : map.entrySet()) {
            try {
                String id = e.getKey().getPath();
                QUESTS.put(id, Quest.parse(id, e.getValue().getAsJsonObject()));
            } catch (Exception ex) {
                KonoRPG.LOGGER.error("Bad quest file {}", e.getKey(), ex);
            }
        }
        KonoRPG.LOGGER.info("Loaded {} quests", QUESTS.size());
    }

    public static Quest get(String id) { return QUESTS.get(id); }

    public static int mainTotal() {
        int n = 0;
        for (Quest q : QUESTS.values()) if (q.category.equals("main")) n++;
        return n;
    }

    // ---------------- eligibility ----------------

    private static String lockReason(PlayerRpgData d, Quest q, long gt) {
        Long cd = d.questCd.get(q.id);
        if (cd != null && gt < cd) return "Em cooldown: " + ((cd - gt) / 1200 + 1) + " min";
        if (d.level < q.minLevel) return "Requer nível " + q.minLevel;
        if (d.rank().ordinal() < q.minRank.ordinal()) return "Requer rank " + q.minRank.name();
        for (String r : q.requires) if (!d.completed.contains(r)) {
            Quest rq = QUESTS.get(r);
            return "Requer concluir: " + (rq == null ? r : rq.name);
        }
        if (!Requirements.check(d, q.require)) return "Requisitos especiais não cumpridos";
        return null;
    }

    private static int count(ServerPlayer p, String itemId) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) {
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(itemId)) n += s.getCount();
        }
        return n;
    }

    private static void remove(ServerPlayer p, String itemId, int amount) {
        for (ItemStack s : p.getInventory().items) {
            if (amount <= 0) return;
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(itemId)) {
                int take = Math.min(amount, s.getCount());
                s.shrink(take);
                amount -= take;
            }
        }
    }

    // ---------------- accept / abandon / claim ----------------

    public static boolean accept(ServerPlayer p, String id, boolean force) {
        PlayerRpgData d = RpgManager.data(p);
        Quest q = QUESTS.get(id);
        if (q == null || !Config.QUESTS_ENABLED.get()) return false;
        if (!force) {
            if (!d.registered) { RpgManager.msg(p, "Registre-se na Guilda primeiro."); return false; }
            if (d.quests.containsKey(id)) return false;
            if (d.completed.contains(id) && !q.repeatable) return false;
            if (lockReason(d, q, p.level().getGameTime()) != null) { RpgManager.msg(p, "Quest bloqueada: " + lockReason(d, q, p.level().getGameTime())); return false; }
            long active = d.quests.keySet().stream().filter(k -> { Quest x = QUESTS.get(k); return x != null && !x.category.equals("main"); }).count();
            if (active >= MAX_ACTIVE) { RpgManager.msg(p, "Você já tem quests demais (" + MAX_ACTIVE + ")."); return false; }
            if (q.category.equals("board") && !nearBoard(p)) { RpgManager.msg(p, "Quests da Guilda só podem ser aceitas no mural."); return false; }
        } else if (d.quests.containsKey(id)) {
            return false;
        }
        long deadline = q.timeLimitMin > 0 ? p.level().getGameTime() + q.timeLimitMin * 1200L : 0;
        QuestProgress qp = new QuestProgress(0, deadline);
        if (q.type.equals("collect")) qp.progress = Math.min(q.amount, count(p, q.target));
        d.quests.put(id, qp);
        RpgManager.msg(p, "Quest aceita: " + q.name);
        if (!q.humor.isEmpty()) RpgManager.msg(p, "\u00a77" + q.humor);
        CompanionManager.react(p, "quest_accept");
        verify(p);
        RpgManager.sync(p);
        return true;
    }

    public static void abandon(ServerPlayer p, String id) {
        PlayerRpgData d = RpgManager.data(p);
        Quest q = QUESTS.get(id);
        if (q == null || q.category.equals("main") || d.quests.remove(id) == null) return;
        RpgManager.msg(p, "Quest abandonada: " + q.name);
        RpgManager.sync(p);
    }

    /** Quest rewards for board/main/special quests are only paid by the Guild receptionist (dialogue). */
    public static int claimAll(ServerPlayer p) {
        PlayerRpgData d = RpgManager.data(p);
        int n = 0;
        for (String id : new java.util.ArrayList<>(d.quests.keySet())) {
            Quest q = QUESTS.get(id);
            if (q == null || q.category.equals("favor") || q.category.equals("party")) continue;
            if (claim(p, id)) n++;
        }
        return n;
    }

    /** Favor quests are handed in to the NPC that gave them (dialogue effect). */
    public static boolean claimFavor(ServerPlayer p, String id) {
        Quest q = QUESTS.get(id);
        if (q == null || !q.category.equals("favor")) return false;
        return claim(p, id);
    }

    private static boolean claim(ServerPlayer p, String id) {
        PlayerRpgData d = RpgManager.data(p);
        Quest q = QUESTS.get(id);
        QuestProgress qp = d.quests.get(id);
        if (q == null || qp == null) return false;
        if (q.type.equals("collect")) qp.progress = Math.min(q.amount, count(p, q.target));
        if (qp.progress < q.amount) return false;
        if (q.type.equals("collect")) remove(p, q.target, q.amount);
        d.quests.remove(id);
        d.completed.add(id);
        if (q.cooldownMin > 0) d.questCd.put(id, p.level().getGameTime() + q.cooldownMin * 1200L);
        d.questsCompleted++;
        if (q.category.equals("main")) d.mainDone++;
        RpgManager.grantXp(p, q.xp);
        RpgManager.grantMoney(p, q.money);
        for (var e : q.rep.entrySet()) RpgManager.addRep(p, e.getKey(), e.getValue());
        for (String[] it : q.items) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(it[0]));
            com.konorpg.rpg.Perks.give(p, new ItemStack(item, Integer.parseInt(it[1])));
        }
        RpgManager.title(p, "QUEST COMPLETA", q.name);
        RpgManager.msg(p, "Recompensa: " + q.xp + " XP, " + q.money + " Eris.");
        RpgManager.refreshRank(p);
        CompanionManager.react(p, "quest_done");
        for (String u : q.unlocks) accept(p, u, true);
        onEvent(p, "quest_done:" + id, 1);
        RpgManager.sync(p);
        return true;
    }

    public static boolean isReady(PlayerRpgData d, String id) {
        Quest q = QUESTS.get(id);
        QuestProgress qp = d.quests.get(id);
        return q != null && qp != null && qp.progress >= q.amount;
    }

    /** Re-checks state-based ("event") objectives so the order of actions never matters (button "Verificar"). */
    public static void verify(ServerPlayer p) {
        PlayerRpgData d = RpgManager.data(p);
        boolean changed = false;
        for (var e : d.quests.entrySet()) {
            Quest q = QUESTS.get(e.getKey());
            if (q == null || !q.type.equals("event")) continue;
            QuestProgress qp = e.getValue();
            if (qp.progress >= q.amount) continue;
            boolean ok = switch (q.target) {
                case "register_guild" -> d.registered;
                case "choose_class" -> !d.classId.equals("adventurer");
                default -> {
                    if (q.target.startsWith("rank:")) yield d.rank().ordinal() >= com.konorpg.rpg.Rank.byName(q.target.substring(5), com.konorpg.rpg.Rank.F).ordinal();
                    if (q.target.startsWith("level:")) yield d.level >= Integer.parseInt(q.target.substring(6));
                    yield false;
                }
            };
            if (ok) { qp.progress = q.amount; changed = true; notifyProgress(p, q, qp); }
        }
        if (changed) RpgManager.sync(p);
    }

    private static boolean nearBoard(ServerPlayer p) {
        BlockPos c = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-5, -3, -5), c.offset(5, 3, 5))) {
            if (p.level().getBlockState(pos).is(com.konorpg.registry.ModBlocks.QUEST_BOARD.get())) return true;
        }
        return false;
    }

    // ---------------- progress hooks ----------------

    public static void onKill(ServerPlayer p, EntityType<?> type) {
        PlayerRpgData d = RpgManager.data(p);
        String key = EntityType.getKey(type).toString();
        boolean changed = false;
        for (var e : d.quests.entrySet()) {
            Quest q = QUESTS.get(e.getKey());
            if (q == null || !(q.type.equals("kill") || q.type.equals("boss")) || !q.target.equals(key)) continue;
            QuestProgress qp = e.getValue();
            if (qp.progress < q.amount) {
                qp.progress++;
                changed = true;
                notifyProgress(p, q, qp);
            }
        }
        if (changed) RpgManager.sync(p);
    }

    public static void onEvent(ServerPlayer p, String name, int amount) {
        PlayerRpgData d = RpgManager.data(p);
        boolean changed = false;
        for (var e : d.quests.entrySet()) {
            Quest q = QUESTS.get(e.getKey());
            if (q == null || !q.type.equals("event") || !q.target.equals(name)) continue;
            QuestProgress qp = e.getValue();
            if (qp.progress < q.amount) {
                qp.progress = Math.min(q.amount, qp.progress + amount);
                changed = true;
                notifyProgress(p, q, qp);
            }
        }
        if (changed) RpgManager.sync(p);
    }

    private static void notifyProgress(ServerPlayer p, Quest q, QuestProgress qp) {
        if (qp.progress >= q.amount) RpgManager.msg(p, "Objetivo concluído: " + q.name + " - volte à Guilda para receber a recompensa!");
        else p.displayClientMessage(net.minecraft.network.chat.Component.literal(q.name + ": " + qp.progress + "/" + q.amount), true);
    }

    /** Called once per second per player. */
    public static void tickPlayer(ServerPlayer p, PlayerRpgData d) {
        if (d.quests.isEmpty()) return;
        verify(p);
        long gt = p.level().getGameTime();
        WorldRpgData w = null;
        boolean changed = false;
        for (var it : new java.util.ArrayList<>(d.quests.entrySet())) {
            Quest q = QUESTS.get(it.getKey());
            QuestProgress qp = it.getValue();
            if (q == null) { d.quests.remove(it.getKey()); changed = true; continue; }
            if (qp.deadline > 0 && gt > qp.deadline && qp.progress < q.amount) {
                d.quests.remove(it.getKey());
                RpgManager.msg(p, "Tempo esgotado! Quest falhou: " + q.name);
                RpgManager.addRep(p, "guild", -3);
                changed = true;
                continue;
            }
            if (q.type.equals("collect")) {
                int c = Math.min(q.amount, count(p, q.target));
                if (c != qp.progress) { qp.progress = c; changed = true; if (c >= q.amount) notifyProgress(p, q, qp); }
            } else if (q.type.equals("explore") && qp.progress < q.amount) {
                if (w == null) w = WorldRpgData.get(p.server);
                BlockPos pos = switch (q.target) {
                    case "city" -> w.city;
                    case "guild" -> w.guildHall;
                    case "gate" -> w.gate;
                    case "dungeon" -> w.dungeon;
                    default -> null;
                };
                if (pos != null && p.blockPosition().closerThan(pos, 14)) { qp.progress = q.amount; changed = true; notifyProgress(p, q, qp); }
            }
        }
        if (changed) RpgManager.sync(p);
    }

    // ---------------- board ----------------

    public static void openBoard(ServerPlayer p) {
        PlayerRpgData d = RpgManager.data(p);
        if (!d.registered) { RpgManager.msg(p, "Você precisa se registrar na Guilda para ver as quests. Fale com a recepcionista."); return; }
        CompoundTag t = new CompoundTag();
        ListTag list = new ListTag();
        long gt = p.level().getGameTime();
        java.util.Set<String> shown = rotation(p);
        for (Quest q : QUESTS.values()) {
            if (q.category.equals("party") || q.category.equals("favor")) continue;
            boolean active = d.quests.containsKey(q.id);
            if (!active && q.category.equals("board") && q.repeatable && !shown.contains(q.id)) continue;
            if (!active && d.completed.contains(q.id) && !q.repeatable) continue;
            String reason = active ? null : lockReason(d, q, gt);
            if (q.category.equals("main") && !active && reason != null) continue;
            CompoundTag c = new CompoundTag();
            c.putString("id", q.id);
            c.putString("name", q.name);
            c.putString("desc", q.desc);
            c.putString("loc", q.location);
            c.putString("type", q.type);
            c.putString("target", q.target);
            c.putInt("amount", q.amount);
            c.putInt("xp", q.xp);
            c.putInt("money", q.money);
            StringBuilder rs = new StringBuilder();
            q.rep.forEach((k, v) -> rs.append(k).append(' ').append(v > 0 ? "+" : "").append(v).append("  "));
            c.putString("rep", rs.toString().trim());
            c.putInt("diff", q.difficulty);
            c.putString("rank", q.minRank.name());
            c.putString("humor", q.humor);
            c.putString("cat", q.category);
            c.putInt("timeMin", q.timeLimitMin);
            QuestProgress qp = d.quests.get(q.id);
            c.putInt("status", active ? (qp.progress >= q.amount ? 2 : 1) : reason == null ? 0 : 3);
            c.putInt("progress", qp == null ? 0 : qp.progress);
            c.putString("reason", reason == null ? "" : reason);
            list.add(c);
        }
        t.put("quests", list);
        t.putLong("gt", gt);
        Net.openScreen(p, "board", t);
    }

    /** Compact info about the player's active quests, sent with every sync (the client has no quest definitions). */
    public static ListTag activeInfo(PlayerRpgData d) {
        ListTag list = new ListTag();
        for (var e : d.quests.entrySet()) {
            Quest q = QUESTS.get(e.getKey());
            if (q == null) continue;
            CompoundTag c = new CompoundTag();
            c.putString("id", q.id);
            c.putString("name", q.name);
            c.putString("desc", q.desc);
            c.putString("type", q.type);
            c.putString("target", q.target);
            c.putString("cat", q.category);
            c.putInt("amount", q.amount);
            list.add(c);
        }
        return list;
    }

    /** Board quests rotate every 2 in-game days (~40 min): a different subset is offered. */
    private static java.util.Set<String> rotation(ServerPlayer p) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (Quest q : QUESTS.values()) if (q.category.equals("board") && q.repeatable) ids.add(q.id);
        java.util.Collections.sort(ids);
        long slot = p.level().getDayTime() / 48000L;
        java.util.Collections.shuffle(ids, new java.util.Random(slot * 7919L + 17));
        return new java.util.HashSet<>(ids.subList(0, Math.min(ids.size(), Math.max(6, ids.size() * 2 / 3))));
    }

    public static void resetPlayerQuests(PlayerRpgData d) { d.quests.clear(); d.completed.clear(); d.mainDone = 0; }
}
