package com.example.damageindicator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Indicador de dano estilo ToroHealth (solo cliente).
 *
 * - Cada tick compara la vida de las entidades cercanas.
 * - Si cambia, crea un numero flotante (rojo = dano, verde = curacion).
 * - Se dibuja en el HUD proyectando la posicion 3D a pantalla con matematica propia,
 *   asi no dependemos de la API de render del mundo (que cambia mucho entre versiones).
 */
public class DamageIndicatorClient implements ClientModInitializer {
    public static final String MOD_ID = "damageindicator";

    // ---- Ajustes (cambialos a gusto) ----
    private static final int LIFETIME_TICKS = 30;      // duracion del numero (20 ticks = 1 s)
    private static final double RANGE = 32.0;          // radio de deteccion en bloques
    private static final float RISE_PER_TICK = 0.03f;  // cuanto sube el numero
    private static final int COLOR_DAMAGE = 0xFF5555;
    private static final int COLOR_HEAL = 0x55FF55;
    private static final boolean SHOW_SELF = false;    // mostrar tambien tu propio dano

    private final Map<Integer, Float> lastHealth = new HashMap<>();
    private final List<Indicator> indicators = new ArrayList<>();
    private final Random random = new Random();

    private static final class Indicator {
        final Vec3 origin;
        final float amount;
        int age = 0;

        Indicator(Vec3 origin, float amount) {
            this.origin = origin;
            this.amount = amount;
        }
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "indicators"),
                this::render);
    }

    // ------------------------------------------------------------------ logica

    private void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            lastHealth.clear();
            indicators.clear();
            return;
        }
        if (mc.isPaused()) return;

        Set<Integer> seen = new HashSet<>();
        List<Entity> nearby = mc.level.getEntities(
                (Entity) null,
                mc.player.getBoundingBox().inflate(RANGE),
                e -> e instanceof LivingEntity);

        for (Entity entity : nearby) {
            LivingEntity living = (LivingEntity) entity;
            if (!SHOW_SELF && living == mc.player) continue;

            int id = living.getId();
            seen.add(id);

            float hp = living.getHealth();
            Float prev = lastHealth.put(id, hp);
            if (prev != null && Math.abs(hp - prev) >= 0.05f) {
                spawn(living, hp - prev);
            }
        }
        lastHealth.keySet().retainAll(seen);

        for (Iterator<Indicator> it = indicators.iterator(); it.hasNext(); ) {
            Indicator ind = it.next();
            ind.age++;
            if (ind.age > LIFETIME_TICKS) it.remove();
        }
    }

    private void spawn(LivingEntity entity, float delta) {
        Vec3 pos = new Vec3(
                entity.getX() + (random.nextDouble() - 0.5) * 0.7,
                entity.getY() + entity.getBbHeight() + 0.15,
                entity.getZ() + (random.nextDouble() - 0.5) * 0.7);
        indicators.add(new Indicator(pos, delta));
    }

    // ------------------------------------------------------------------ render

    private void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || indicators.isEmpty()) return;

        float pt = deltaTracker.getGameTimeDeltaPartialTick(false);

        // Camara (aprox. primera persona: ojos del jugador + su direccion de mirada)
        Vec3 eye = mc.player.getEyePosition(pt);
        double yaw = Math.toRadians(mc.player.getViewYRot(pt));
        double pitch = Math.toRadians(mc.player.getViewXRot(pt));

        Vec3 forward = new Vec3(
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(forward);

        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        double aspect = (double) w / (double) h;
        double tanHalfFov = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);

        for (Indicator ind : indicators) {
            float age = ind.age + pt;
            Vec3 pos = ind.origin.add(0, age * RISE_PER_TICK, 0);
            Vec3 d = pos.subtract(eye);

            double zc = d.dot(forward);
            if (zc < 0.5) continue; // detras de la camara o pegado a ella

            double ndcX = d.dot(right) / (zc * tanHalfFov * aspect);
            double ndcY = d.dot(up) / (zc * tanHalfFov);
            if (Math.abs(ndcX) > 1.2 || Math.abs(ndcY) > 1.2) continue; // fuera de pantalla

            float sx = (float) (w / 2.0 * (1.0 + ndcX));
            float sy = (float) (h / 2.0 * (1.0 - ndcY));

            float scale = Mth.clamp((float) (8.0 / zc), 0.6f, 2.5f);

            float life = age / LIFETIME_TICKS;
            int alpha = life < 0.7f ? 255 : (int) (255 * (1f - (life - 0.7f) / 0.3f));
            alpha = Mth.clamp(alpha, 8, 255);

            boolean heal = ind.amount > 0;
            int rgb = heal ? COLOR_HEAL : COLOR_DAMAGE;
            int color = (alpha << 24) | rgb;
            String text = (heal ? "+" : "-") + format(Math.abs(ind.amount));

            graphics.pose().pushMatrix();
            graphics.pose().translate(sx, sy);
            graphics.pose().scale(scale, scale);
            graphics.text(mc.font, text, -mc.font.width(text) / 2, -4, color);
            graphics.pose().popMatrix();
        }
    }

    private static String format(float value) {
        if (Math.abs(value - Math.round(value)) < 0.05f) {
            return Integer.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
