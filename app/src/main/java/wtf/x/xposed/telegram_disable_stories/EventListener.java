package wtf.x.xposed.telegram_disable_stories;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class EventListener implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(final LoadPackageParam aparam) {
        if (!"org.telegram.messenger".equals(aparam.packageName)) {
            return;
        }
        XposedHelpers.findAndHookMethod("android.content.SharedPreferences", aparam.classLoader, "getString", String.class, String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // It's a preference, I don't find a switch do disable it tho
                if ("storiesPosting".equals(param.args[0]) || "storiesEntities".equals(param.args[0])) {
                    param.setResult("disabled");
                }
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
