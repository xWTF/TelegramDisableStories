package wtf.x.xposed.telegram_disable_stories;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class EventListener implements IXposedHookLoadPackage {
    private static final String LOG_TAG = "[DisableStories] ";
    private static final XC_MethodHook RETURN_FALSE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(false);
        }
    };
    private static final XC_MethodHook RETURN_EMPTY_LIST = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            // Callers may modify the result, so do not share an empty list.
            param.setResult(new ArrayList<>());
        }
    };

    @Override
    public void handleLoadPackage(final LoadPackageParam aparam) {
        if (!"org.telegram.messenger".equals(aparam.packageName)) {
            return;
        }

        Class<?> messagesController = findClass("org.telegram.messenger.MessagesController", aparam.classLoader);
        hookMethods(messagesController, "storiesEnabled", boolean.class, RETURN_FALSE);
        hookMethods(messagesController, "storyEntitiesAllowed", boolean.class, RETURN_FALSE);

        if (messagesController == null || !aparam.packageName.equals(aparam.processName)) {
            return;
        }

        try {
            // This retained messenger method exposes the actual type even after R8 renames it.
            Class<?> storiesController = XposedHelpers.findMethodExact(messagesController, "getStoriesController").getReturnType();
            XposedBridge.log(LOG_TAG + "Story controller: " + storiesController.getName());
            if (storiesController.getName().equals("org.telegram.ui.Stories.StoriesController")) {
                for (String name : new String[]{"hasStories", "hasSelfStories", "hasOnlySelfStories", "hasHiddenStories", "canPostStories"}) {
                    hookMethods(storiesController, name, boolean.class, RETURN_FALSE);
                }
                // Some UI paths read the lists directly instead of checking hasStories().
                hookMethods(storiesController, "getDialogListStories", ArrayList.class, RETURN_EMPTY_LIST);
                hookMethods(storiesController, "getHiddenList", ArrayList.class, RETURN_EMPTY_LIST);
            }

            Class<?> dialogsActivity;
            try {
                dialogsActivity = XposedHelpers.findClass("org.telegram.ui.DialogsActivity", aparam.classLoader);
            } catch (XposedHelpers.ClassNotFoundError ignored) {
                StoryUiLocator.Target target = StoryUiLocator.find(apkPaths(aparam.appInfo), storiesController.getName());
                dialogsActivity = XposedHelpers.findClass(target.className, aparam.classLoader);
                XposedHelpers.findAndHookMethod(dialogsActivity, target.methodName, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // Leave the initially hidden Story row and its layout state untouched.
                        param.setResult(null);
                    }
                });
                for (String name : target.controllerChecks) {
                    XposedHelpers.findAndHookMethod(storiesController, name, RETURN_FALSE);
                    XposedBridge.log(LOG_TAG + "Story check: " + storiesController.getName() + "." + name + "()");
                }
                XposedBridge.log(LOG_TAG + "Story visibility: " + target.className + "." + target.methodName);
            }
            hookStoryButton(dialogsActivity, aparam.classLoader);
        } catch (Throwable error) {
            XposedBridge.log(LOG_TAG + "Could not install Story UI hooks: " + error);
            XposedBridge.log(error);
        }
    }

    private static String[] apkPaths(ApplicationInfo appInfo) {
        ArrayList<String> paths = new ArrayList<>();
        paths.add(appInfo.sourceDir);
        if (Build.VERSION.SDK_INT >= 21 && appInfo.splitSourceDirs != null) {
            for (String path : appInfo.splitSourceDirs) {
                paths.add(path);
            }
        }
        return paths.toArray(new String[0]);
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return XposedHelpers.findClass(name, classLoader);
        } catch (Throwable error) {
            XposedBridge.log(LOG_TAG + "Skipping " + name + ": " + error);
            return null;
        }
    }

    private static void hookMethods(Class<?> targetClass, String name, Class<?> returnType, XC_MethodHook callback) {
        if (targetClass == null) {
            return;
        }

        int hookedCount = 0;
        try {
            for (Method method : targetClass.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getReturnType() != returnType) {
                    continue;
                }

                try {
                    XposedBridge.hookMethod(method, callback);
                    hookedCount++;
                } catch (Throwable error) {
                    XposedBridge.log(LOG_TAG + "Could not hook " + method + ": " + error);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(LOG_TAG + "Could not inspect " + targetClass.getName() + ": " + error);
        }
        XposedBridge.log(LOG_TAG + targetClass.getName() + "." + name + ": " + hookedCount + " hooked");
    }

    private static void hookStoryButton(Class<?> dialogsActivity, ClassLoader classLoader) {
        final Class<?> localeController = XposedHelpers.findClass("org.telegram.messenger.LocaleController", classLoader);
        final Class<?> stringResources = XposedHelpers.findClass("org.telegram.messenger.R$string", classLoader);
        final int labelId = XposedHelpers.getStaticIntField(stringResources, "StoryPrivacyButtonPost");
        XC_MethodHook hideStoryButton = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable()) {
                    return;
                }
                CharSequence label = (CharSequence) XposedHelpers.callStaticMethod(localeController, "getString", labelId);
                if (TextUtils.isEmpty(label)) {
                    XposedBridge.log(LOG_TAG + "Missing Story post button label");
                    return;
                }
                for (Field field : dialogsActivity.getDeclaredFields()) {
                    if (!View.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    View button = (View) field.get(param.thisObject);
                    if (button == null || !TextUtils.equals(label, button.getContentDescription())) {
                        continue;
                    }
                    // Detaching survives Telegram's later visibility animations without
                    // hooking shared View methods or changing the new-message button.
                    if (button.getParent() instanceof ViewGroup) {
                        ((ViewGroup) button.getParent()).removeView(button);
                        XposedBridge.log(LOG_TAG + "Removed Story post button");
                    }
                    button.setVisibility(View.GONE);
                }
            }
        };
        hookMethods(dialogsActivity, "createView", View.class, hideStoryButton);
    }
}
