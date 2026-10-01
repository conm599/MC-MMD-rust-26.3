/** 负责 Forge 侧模块配置界面。 */
package com.shiroha.mmdskin.forge.config;

import com.shiroha.mmdskin.asset.catalog.ModelCatalogEntry;
import com.shiroha.mmdskin.config.ConfigData;
import com.shiroha.mmdskin.config.PhysicsConfigSnapshot;
import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.entity.MobReplacementTargets;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Forge 模组设置界面。 */
public class ModConfigScreen {

    public static Screen create(Screen parent) {
        return createSettingsScreen(parent);
    }

    static Screen createSettingsScreen(Screen parent) {
        ConfigData data = MmdSkinConfig.getData();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("gui.mmdskin.mod_settings.title"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory renderCategory = builder.getOrCreateCategory(
            Component.translatable("gui.mmdskin.mod_settings.category.render"));

        renderCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.opengl_lighting"),
                data.openGLEnableLighting)
            .setDefaultValue(true)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.opengl_lighting.tooltip"))
            .setSaveConsumer(value -> data.openGLEnableLighting = value)
            .build());

        renderCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.mmd_shader"),
                data.mmdShaderEnabled)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.mmd_shader.tooltip"))
            .setSaveConsumer(value -> data.mmdShaderEnabled = value)
            .build());

        renderCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.first_person_model"),
                data.firstPersonModelEnabled)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.first_person_model.tooltip"))
            .setSaveConsumer(value -> data.firstPersonModelEnabled = value)
            .build());

        renderCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.first_person_camera_forward_offset"),
                Math.round(data.firstPersonCameraForwardOffset * 1000.0F),
                -100, 500)
            .setDefaultValue(0)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.first_person_camera_forward_offset.tooltip"))
            .setTextGetter(value -> Component.literal(String.format("%.3f", value.intValue() / 1000.0F)))
            .setSaveConsumer(value -> data.firstPersonCameraForwardOffset = value.intValue() / 1000.0F)
            .build());

        renderCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.first_person_camera_vertical_offset"),
                Math.round(data.firstPersonCameraVerticalOffset * 1000.0F),
                -500, 500)
            .setDefaultValue(0)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.first_person_camera_vertical_offset.tooltip"))
            .setTextGetter(value -> Component.literal(String.format("%.3f", value.intValue() / 1000.0F)))
            .setSaveConsumer(value -> data.firstPersonCameraVerticalOffset = value.intValue() / 1000.0F)
            .build());

        ConfigCategory performanceCategory = builder.getOrCreateCategory(
            Component.translatable("gui.mmdskin.mod_settings.category.performance"));

        performanceCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.model_pool_max"),
                data.modelPoolMaxCount, 5, 100)
            .setDefaultValue(20)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.model_pool_max.tooltip"))
            .setSaveConsumer(value -> data.modelPoolMaxCount = value)
            .build());

        performanceCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.gpu_skinning"),
                data.gpuSkinningEnabled)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.gpu_skinning.tooltip"))
            .setSaveConsumer(value -> data.gpuSkinningEnabled = value)
            .build());

        performanceCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.gpu_morph"),
                data.gpuMorphEnabled)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.gpu_morph.tooltip"))
            .setSaveConsumer(value -> data.gpuMorphEnabled = value)
            .build());

        performanceCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.max_bones"),
                data.maxBones, 512, 4096)
            .setDefaultValue(2048)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.max_bones.tooltip"))
            .setSaveConsumer(value -> data.maxBones = value)
            .build());

        performanceCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.texture_cache_budget"),
                data.textureCacheBudgetMB, 64, 1024)
            .setDefaultValue(256)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.texture_cache_budget.tooltip"))
            .setTextGetter(value -> Component.literal(value + " MB"))
            .setSaveConsumer(value -> data.textureCacheBudgetMB = value)
            .build());

        performanceCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.max_visible_models"),
                data.maxVisibleModelsPerFrame, 1, 50)
            .setDefaultValue(10)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.max_visible_models.tooltip"))
            .setSaveConsumer(value -> data.maxVisibleModelsPerFrame = value)
            .build());

        ConfigCategory toonCategory = builder.getOrCreateCategory(
            Component.translatable("gui.mmdskin.mod_settings.category.toon"));

        toonCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.toon_enabled"),
                data.toonRenderingEnabled)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_enabled.tooltip"))
            .setSaveConsumer(value -> data.toonRenderingEnabled = value)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_levels"),
                data.toonLevels, 2, 5)
            .setDefaultValue(4)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_levels.tooltip"))
            .setSaveConsumer(value -> data.toonLevels = value)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_rim_power"),
                (int) (data.toonRimPower * 10), 10, 100)
            .setDefaultValue(56)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_rim_power.tooltip"))
            .setSaveConsumer(value -> data.toonRimPower = value / 10.0f)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_rim_intensity"),
                (int) (data.toonRimIntensity * 100), 0, 100)
            .setDefaultValue(2)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_rim_intensity.tooltip"))
            .setSaveConsumer(value -> data.toonRimIntensity = value / 100.0f)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_shadow_r"),
                (int) (data.toonShadowR * 100), 0, 100)
            .setDefaultValue(78)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_shadow.tooltip"))
            .setSaveConsumer(value -> data.toonShadowR = value / 100.0f)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_shadow_g"),
                (int) (data.toonShadowG * 100), 0, 100)
            .setDefaultValue(84)
            .setSaveConsumer(value -> data.toonShadowG = value / 100.0f)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_shadow_b"),
                (int) (data.toonShadowB * 100), 0, 100)
            .setDefaultValue(94)
            .setSaveConsumer(value -> data.toonShadowB = value / 100.0f)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_specular_power"),
                (int) data.toonSpecularPower, 1, 128)
            .setDefaultValue(96)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_specular_power.tooltip"))
            .setSaveConsumer(value -> data.toonSpecularPower = value)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_specular_intensity"),
                (int) (data.toonSpecularIntensity * 100), 0, 100)
            .setDefaultValue(2)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_specular_intensity.tooltip"))
            .setSaveConsumer(value -> data.toonSpecularIntensity = value / 100.0f)
            .build());

        toonCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.toon_outline"),
                data.toonOutlineEnabled)
            .setDefaultValue(true)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_outline.tooltip"))
            .setSaveConsumer(value -> data.toonOutlineEnabled = value)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_outline_width"),
                (int) (data.toonOutlineWidth * 1000), 1, 100)
            .setDefaultValue(2)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_outline_width.tooltip"))
            .setSaveConsumer(value -> data.toonOutlineWidth = value / 1000.0f)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_outline_r"),
                (int) (data.toonOutlineR * 100), 0, 100)
            .setDefaultValue(6)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.toon_outline_color.tooltip"))
            .setSaveConsumer(value -> data.toonOutlineR = value / 100.0f)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_outline_g"),
                (int) (data.toonOutlineG * 100), 0, 100)
            .setDefaultValue(8)
            .setSaveConsumer(value -> data.toonOutlineG = value / 100.0f)
            .build());

        toonCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.toon_outline_b"),
                (int) (data.toonOutlineB * 100), 0, 100)
            .setDefaultValue(12)
            .setSaveConsumer(value -> data.toonOutlineB = value / 100.0f)
            .build());

        ConfigCategory physicsCategory = builder.getOrCreateCategory(
            Component.translatable("gui.mmdskin.mod_settings.category.physics"));

        physicsCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.physics_enabled"),
                data.physicsEnabled)
            .setDefaultValue(true)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_enabled.tooltip"))
            .setSaveConsumer(value -> data.physicsEnabled = value)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.physics_gravity"),
                (int) (data.physicsGravityY * -1), 10, 200)
            .setDefaultValue(98)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_gravity.tooltip"))
            .setSaveConsumer(value -> data.physicsGravityY = value * -1.0f)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.physics_fps"),
                (int) data.physicsFps, 30, 120)
            .setDefaultValue(60)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_fps.tooltip"))
            .setSaveConsumer(value -> data.physicsFps = value)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.physics_substeps"),
                data.physicsMaxSubstepCount, 1, 10)
            .setDefaultValue(5)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_substeps.tooltip"))
            .setSaveConsumer(value -> data.physicsMaxSubstepCount = value)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.physics_inertia"),
                (int) (data.physicsInertiaStrength * 100), 0, 300)
            .setDefaultValue(50)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_inertia.tooltip"))
            .setSaveConsumer(value -> data.physicsInertiaStrength = value / 100.0f)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.physics_max_linear_velocity"),
                (int) data.physicsMaxLinearVelocity, 0, 100)
            .setDefaultValue(20)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_max_linear_velocity.tooltip"))
            .setSaveConsumer(value -> data.physicsMaxLinearVelocity = value)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.physics_max_angular_velocity"),
                (int) data.physicsMaxAngularVelocity, 0, 100)
            .setDefaultValue(20)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_max_angular_velocity.tooltip"))
            .setSaveConsumer(value -> data.physicsMaxAngularVelocity = value)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.physics_joints_enabled"),
                data.physicsJointsEnabled)
            .setDefaultValue(true)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_joints_enabled.tooltip"))
            .setSaveConsumer(value -> data.physicsJointsEnabled = value)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.physics_kinematic_filter"),
                data.physicsKinematicFilter)
            .setDefaultValue(true)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_kinematic_filter.tooltip"))
            .setSaveConsumer(value -> data.physicsKinematicFilter = value)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.physics_debug_log"),
                data.physicsDebugLog)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.physics_debug_log.tooltip"))
            .setSaveConsumer(value -> data.physicsDebugLog = value)
            .build());

        physicsCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.max_physics_models"),
                data.maxPhysicsModelsPerFrame, 1, 50)
            .setDefaultValue(10)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.max_physics_models.tooltip"))
            .setSaveConsumer(value -> data.maxPhysicsModelsPerFrame = value)
            .build());

        ConfigCategory debugCategory = builder.getOrCreateCategory(
            Component.translatable("gui.mmdskin.mod_settings.category.debug"));

        debugCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.debug_hud"),
                data.debugHudEnabled)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.debug_hud.tooltip"))
            .setSaveConsumer(value -> data.debugHudEnabled = value)
            .build());

        ConfigCategory vrCategory = builder.getOrCreateCategory(
            Component.translatable("gui.mmdskin.mod_settings.category.vr"));

        vrCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.vr_enabled"),
                data.vrEnabled)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.vr_enabled.tooltip"))
            .setSaveConsumer(value -> data.vrEnabled = value)
            .build());

        vrCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.vr_arm_ik_strength"),
                Math.round(data.vrArmIKStrength * 100.0F),
                0, 100)
            .setDefaultValue(100)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.vr_arm_ik_strength.tooltip"))
            .setTextGetter(value -> Component.literal(value + "%"))
            .setSaveConsumer(value -> data.vrArmIKStrength = value.intValue() / 100.0F)
            .build());

        ConfigCategory mobReplacementCategory = builder.getOrCreateCategory(
            Component.translatable("gui.mmdskin.mod_settings.category.mob_replacement"));

        mobReplacementCategory.addEntry(entryBuilder
            .startTextDescription(Component.translatable("gui.mmdskin.mod_settings.mob_replacement.description"))
            .build());

        for (MobReplacementTargets.Target target : MobReplacementTargets.all()) {
            String entityTypeId = target.entityTypeId().toString();
            mobReplacementCategory.addEntry(new MobReplacementListEntry(
                target,
                getMobReplacementValue(data, entityTypeId),
                value -> saveMobReplacementSelection(data, entityTypeId, value)
            ));
        }

        ConfigCategory ragdollCategory = builder.getOrCreateCategory(
            Component.translatable("gui.mmdskin.mod_settings.category.ragdoll"));

        ragdollCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.ragdoll_enabled"),
                data.ragdollEnabled)
            .setDefaultValue(true)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.ragdoll_enabled.tooltip"))
            .setSaveConsumer(value -> data.ragdollEnabled = value)
            .build());

        // 本项目的 Cloth Config 版本没有下拉组件：保留模式用 int 滑条 0-2 映射三种模式
        ragdollCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.ragdoll_keep_mode"),
                keepModeIndex(data.corpseKeepMode), 0, 2)
            .setDefaultValue(0)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.ragdoll_keep_mode.tooltip"))
            .setTextGetter(value -> Component.translatable(
                KEEP_MODE_LABEL_KEYS[Math.max(0, Math.min(2, value.intValue()))]))
            .setSaveConsumer(value -> data.corpseKeepMode =
                KEEP_MODE_VALUES[Math.max(0, Math.min(2, value.intValue()))])
            .build());

        ragdollCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.ragdoll_keep_radius"),
                (int) data.corpseKeepRadius, 8, 256)
            .setDefaultValue(48)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.ragdoll_keep_radius.tooltip"))
            .setTextGetter(value -> Component.translatable(
                "gui.mmdskin.mod_settings.ragdoll_keep_radius.value", value))
            .setSaveConsumer(value -> data.corpseKeepRadius = value.intValue())
            .build());

        ragdollCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.ragdoll_keep_seconds"),
                (int) data.corpseKeepSeconds, 5, 600)
            .setDefaultValue(60)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.ragdoll_keep_seconds.tooltip"))
            .setTextGetter(value -> Component.translatable(
                "gui.mmdskin.mod_settings.ragdoll_keep_seconds.value", value))
            .setSaveConsumer(value -> data.corpseKeepSeconds = value.intValue())
            .build());

        ragdollCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.ragdoll_max_corpses"),
                data.maxCorpses, 1, 64)
            .setDefaultValue(8)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.ragdoll_max_corpses.tooltip"))
            .setSaveConsumer(value -> data.maxCorpses = value)
            .build());

        ragdollCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.ragdoll_joint_strength"),
                Math.round(data.ragdollJointStrength * 20.0F), 6, 80)
            .setDefaultValue(27)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.ragdoll_joint_strength.tooltip"))
            .setTextGetter(value -> Component.literal(String.format("%.2f", value.intValue() / 20.0F)))
            .setSaveConsumer(value -> data.ragdollJointStrength = value.intValue() / 20.0F)
            .build());

        ragdollCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.ragdoll_hide_poof"),
                data.hideDeathPoof)
            .setDefaultValue(true)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.ragdoll_hide_poof.tooltip"))
            .setSaveConsumer(value -> data.hideDeathPoof = value)
            .build());

        ragdollCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.mmdskin.mod_settings.ragdoll_hijack_death_screen"),
                data.hijackDeathScreen)
            .setDefaultValue(true)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.ragdoll_hijack_death_screen.tooltip"))
            .setSaveConsumer(value -> data.hijackDeathScreen = value)
            .build());

        ragdollCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.mmdskin.mod_settings.ragdoll_death_anim"),
                Math.round(data.ragdollDeathAnimSeconds * 10.0F), 0, 50)
            .setDefaultValue(0)
            .setTooltip(Component.translatable("gui.mmdskin.mod_settings.ragdoll_death_anim.tooltip"))
            .setTextGetter(value -> value.intValue() == 0
                ? Component.translatable("gui.mmdskin.mod_settings.ragdoll_death_anim.off")
                : Component.literal(String.format("%.1fs", value.intValue() / 10.0F)))
            .setSaveConsumer(value -> data.ragdollDeathAnimSeconds = value.intValue() / 10.0F)
            .build());

        builder.setSavingRunnable(() -> saveConfig(data));

        return builder.build();
    }

    private static final String[] KEEP_MODE_VALUES = {"NEARBY", "SECONDS", "FOREVER"};
    private static final String[] KEEP_MODE_LABEL_KEYS = {
        "gui.mmdskin.mod_settings.ragdoll_keep_mode.nearby",
        "gui.mmdskin.mod_settings.ragdoll_keep_mode.seconds",
        "gui.mmdskin.mod_settings.ragdoll_keep_mode.forever"
    };

    static int keepModeIndex(String mode) {
        for (int i = 0; i < KEEP_MODE_VALUES.length; i++) {
            if (KEEP_MODE_VALUES[i].equals(mode)) {
                return i;
            }
        }
        return 0;
    }

    static void saveConfig(ConfigData data) {
        cleanupInvalidMobReplacements(data);
        MmdSkinConfig.save();

        ClientRenderRuntime.get().renderBackendSettings().setGpuSkinningEnabled(data.gpuSkinningEnabled);
        ClientRenderRuntime.get().renderBackendSettings().setShaderEnabled(data.mmdShaderEnabled);
        ClientRenderRuntime.get().modelRepository().reloadAll();
        ClientRenderRuntime.get().applyPhysicsConfig(PhysicsConfigSnapshot.from(data));
    }

    static String getMobReplacementValue(ConfigData data, String entityTypeId) {
        String currentValue = data.mobModelReplacements.getOrDefault(entityTypeId, UIConstants.DEFAULT_MODEL_NAME);
        if (currentValue == null || currentValue.isBlank()) {
            return UIConstants.DEFAULT_MODEL_NAME;
        }
        return currentValue;
    }

    static List<String> createModelSelections() {
        List<String> selections = new ArrayList<>();
        selections.add(UIConstants.DEFAULT_MODEL_NAME);
        for (ModelCatalogEntry modelInfo : ModelCatalogEntry.scanModels()) {
            String folderName = modelInfo.getFolderName();
            if (!folderName.isBlank() && !selections.contains(folderName)) {
                selections.add(folderName);
            }
        }
        return selections;
    }

    static Component toModelSelectionComponent(String modelName) {
        if (modelName == null || modelName.isBlank() || UIConstants.DEFAULT_MODEL_NAME.equals(modelName)) {
            return Component.translatable("gui.mmdskin.mod_settings.mob_replacement.vanilla");
        }
        return Component.literal(modelName);
    }

    static void saveMobReplacementSelection(ConfigData data, String entityTypeId, String value) {
        if (value == null || value.isBlank() || UIConstants.DEFAULT_MODEL_NAME.equals(value)) {
            data.mobModelReplacements.remove(entityTypeId);
            return;
        }
        data.mobModelReplacements.put(entityTypeId, value);
    }

    static void cleanupInvalidMobReplacements(ConfigData data) {
        Iterator<String> iterator = data.mobModelReplacements.values().iterator();
        while (iterator.hasNext()) {
            String modelName = iterator.next();
            if (modelName == null || modelName.isBlank() || ModelCatalogEntry.findByFolderName(modelName) == null) {
                iterator.remove();
            }
        }
    }
}
