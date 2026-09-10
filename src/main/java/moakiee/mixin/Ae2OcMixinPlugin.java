package moakiee.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin 配置插件：用于在运行期按需禁用与其它 mod 冲突的 mixin。
 *
 * 当前唯一作用：当检测到 BiggerStacks 与本 mod 同时加载时，禁用 {@link MixinFriendlyByteBuf}。
 * 两者都会修改 FriendlyByteBuf 的物品堆栈数量序列化方式（byte → int），但协议格式不一致，
 * 同时启用会导致网络包错位，触发 UTFDataFormatException。
 *
 * 检测必须使用 {@link LoadingModList}，因为 mixin 插件加载远早于 Forge 的 ModList 初始化。
 */
public class Ae2OcMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("ae2_overclocked/MixinPlugin");

    private static final String FRIENDLY_BYTE_BUF_MIXIN = "moakiee.mixin.MixinFriendlyByteBuf";
    private static final String BIGGER_STACKS_MOD_ID = "biggerstacks";

    private boolean biggerStacksLoaded = false;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            LoadingModList list = LoadingModList.get();
            biggerStacksLoaded = list != null && list.getModFileById(BIGGER_STACKS_MOD_ID) != null;
        } catch (Throwable t) {
            biggerStacksLoaded = false;
        }
        if (biggerStacksLoaded) {
            LOGGER.info("Detected biggerstacks; MixinFriendlyByteBuf will be skipped to avoid protocol conflict.");
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String modId = null;
        if (targetClassName.startsWith("com.glodblock.github.extendedae.")) modId = "expatternprovider";
        else if (targetClassName.startsWith("net.pedroksl.advanced_ae.")) modId = "advanced_ae";
        else if (targetClassName.startsWith("io.github.lounode.ae2cs.")) modId = "ae2cs";
        if (modId != null) {
            LoadingModList list = LoadingModList.get();
            if (list == null || list.getModFileById(modId) == null) return false;
        }
        if (biggerStacksLoaded && FRIENDLY_BYTE_BUF_MIXIN.equals(mixinClassName)) {
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
