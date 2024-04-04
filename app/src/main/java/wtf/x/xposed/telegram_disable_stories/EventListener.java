package wtf.x.xposed.telegram_disable_stories;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class EventListener implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(final LoadPackageParam aparam) {
        if (!"org.telegram.messenger".equals(aparam.packageName)) {
            return;
        }
        XposedHelpers.findAndHookMethod("org.telegram.messenger.MessagesController", aparam.classLoader, "storiesEnabled", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(false);
            }
        });
        XposedHelpers.findAndHookMethod("org.telegram.messenger.MessagesController", aparam.classLoader, "storyEntitiesAllowed", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(false);
            }
        });
        XposedHelpers.findAndHookMethod("org.telegram.messenger.MessagesController", aparam.classLoader, "storyEntitiesAllowed", "org.telegram.tgnet.TLRPC.User", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(false);
            }
        });
        XposedHelpers.findAndHookMethod("org.telegram.ui.Stories.StoriesController", aparam.classLoader, "hasStories", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(false);
            }
        });
        XposedBridge.log("Hooked telegram stories checks");
    }
}
