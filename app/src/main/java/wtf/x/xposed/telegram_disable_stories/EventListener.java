package wtf.x.xposed.telegram_disable_stories;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class EventListener implements IXposedHookLoadPackage {
    @Override
    @SuppressWarnings("deprecation")
    public void handleLoadPackage(final LoadPackageParam aparam) throws Throwable {
        if (!"org.telegram.messenger".equals(aparam.packageName)) {
            return;
        }
        XposedHelpers.findAndHookMethod("org.telegram.messenger.MessagesController", aparam.classLoader, "storiesEnabled", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.setResult(false);
            }
        });
        XposedHelpers.findAndHookMethod("org.telegram.ui.Stories.StoriesController", aparam.classLoader, "hasStories", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.setResult(false);
            }
        });
    }
}
