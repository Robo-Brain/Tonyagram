package org.telegram.messenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.collection.LongSparseArray;

import org.telegram.tgnet.TLRPC;

import java.util.HashSet;
import java.util.Set;

public final class ChildSafePasswordGate {
    private static final String CHILD_PREFS = "childsafe_prefs";

    private static final String KEY_SEARCH_UNLOCKED_UNTIL = "search_unlocked_until";
    private static final String KEY_JOIN_UNLOCKED_UNTIL = "join_unlocked_until";
    private static final String KEY_ALLOWED_DIALOG_IDS_PREFIX = "allowed_dialog_ids_";

    private static final long SEARCH_UNLOCK_DURATION_MS = 3 * 60 * 1000L;
    private static final long JOIN_UNLOCK_DURATION_MS = 10 * 1000L;

    private static boolean searchPasswordDialogShown;
    private static boolean joinPasswordDialogShown;
    private static boolean newDialogPasswordDialogShown;

    private enum GateType {
        SEARCH,
        JOIN,
        NEW_DIALOG
    }

    private ChildSafePasswordGate() {
    }

    public static boolean requestSearchApprovalIfNeeded(Context context, Runnable approvedAction) {
        return requestApprovalIfNeeded(
                context,
                KEY_SEARCH_UNLOCKED_UNTIL,
                SEARCH_UNLOCK_DURATION_MS,
                GateType.SEARCH,
                "Требуется пароль",
                "Введите пароль, чтобы выполнить поиск и просмотреть рекомендации",
                approvedAction
        );
    }

    public static boolean requestJoinApprovalIfNeeded(Context context, Runnable approvedAction) {
        return requestApprovalIfNeeded(
                context,
                KEY_JOIN_UNLOCKED_UNTIL,
                JOIN_UNLOCK_DURATION_MS,
                GateType.JOIN,
                "Требуется пароль",
                "Введите пароль родителя, чтобы вступить в канал или группу",
                approvedAction
        );
    }

    public static boolean requestNewDialogApprovalIfNeeded(
            int account,
            Context context,
            TLRPC.User user,
            TLRPC.Chat chat,
            Runnable approvedAction
    ) {
        Context prefsContext = context != null ? context : ApplicationLoader.applicationContext;
        long dialogId = resolveDialogId(user, chat);

        if (dialogId == 0 || prefsContext == null) {
            return false;
        }

        ensureAllowedDialogsFilledIfEmpty(account, prefsContext);

        if (isDialogAllowed(prefsContext, account, dialogId)) {
            return false;
        }

        final long finalDialogId = dialogId;
        return requestApprovalIfNeeded(
                context,
                null,
                0L,
                GateType.NEW_DIALOG,
                "Требуется пароль",
                "Введите пароль родителя, чтобы открыть новый чат, канал или группу",
                () -> {
                    allowDialog(prefsContext, account, finalDialogId);
                    if (approvedAction != null) {
                        approvedAction.run();
                    }
                }
        );
    }

    private static boolean requestApprovalIfNeeded(
            Context context,
            String unlockKey,
            long unlockDurationMs,
            GateType gateType,
            String title,
            String message,
            Runnable approvedAction
    ) {
        Context prefsContext = context != null ? context : ApplicationLoader.applicationContext;
        if (prefsContext != null && unlockKey != null && isUnlocked(prefsContext, unlockKey)) {
            return false;
        }

        if (context == null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (ApplicationLoader.applicationContext != null) {
                    Toast.makeText(ApplicationLoader.applicationContext, "Требуется пароль родителя", Toast.LENGTH_SHORT).show();
                }
            });
            return true;
        }

        AndroidUtilities.runOnUIThread(() -> showPasswordDialog(context, unlockKey, unlockDurationMs, gateType, title, message, approvedAction));
        return true;
    }

    private static boolean isUnlocked(Context context, String unlockKey) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(CHILD_PREFS, Context.MODE_PRIVATE);
            long until = prefs.getLong(unlockKey, 0L);
            return System.currentTimeMillis() <= until;
        } catch (Exception e) {
            return false;
        }
    }

    private static void unlockFor(Context context, String unlockKey, long durationMs) {
        if (unlockKey == null || durationMs <= 0) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(CHILD_PREFS, Context.MODE_PRIVATE);
        long until = System.currentTimeMillis() + durationMs;
        prefs.edit().putLong(unlockKey, until).apply();
    }

    private static boolean checkPassword(String entered) {
        return entered != null && entered.equals(BuildConfig.SEARCH_PASSWORD);
    }

    private static void showPasswordDialog(
            Context context,
            String unlockKey,
            long unlockDurationMs,
            GateType gateType,
            String title,
            String message,
            Runnable approvedAction
    ) {
        if (isDialogShown(gateType)) {
            return;
        }
        if (context instanceof Activity && ((Activity) context).isFinishing()) {
            return;
        }

        setDialogShown(gateType, true);

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Пароль");

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(message)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("ОК", (dialog, which) -> {
                    setDialogShown(gateType, false);
                    String entered = input.getText() != null ? input.getText().toString() : "";
                    if (checkPassword(entered)) {
                        unlockFor(context, unlockKey, unlockDurationMs);
                        if (approvedAction != null) {
                            AndroidUtilities.runOnUIThread(approvedAction);
                        }
                    } else {
                        Toast.makeText(context, "Неверный пароль", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", (dialog, which) -> setDialogShown(gateType, false))
                .setOnCancelListener(dialog -> setDialogShown(gateType, false));

        try {
            builder.create().show();
        } catch (Exception e) {
            setDialogShown(gateType, false);
            FileLog.e(e);
        }
    }

    private static long resolveDialogId(TLRPC.User user, TLRPC.Chat chat) {
        if (user != null) {
            return user.id;
        }
        if (chat != null) {
            return -chat.id;
        }
        return 0;
    }

    private static String allowedDialogsKey(int account) {
        return KEY_ALLOWED_DIALOG_IDS_PREFIX + account;
    }

    private static void ensureAllowedDialogsFilledIfEmpty(int account, Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(CHILD_PREFS, Context.MODE_PRIVATE);
            Set<String> allowedDialogs = prefs.getStringSet(allowedDialogsKey(account), null);
            if (allowedDialogs != null && !allowedDialogs.isEmpty()) {
                return;
            }

            HashSet<String> initialAllowedDialogs = new HashSet<>();
            LongSparseArray<TLRPC.Dialog> dialogs = MessagesController.getInstance(account).dialogs_dict;
            if (dialogs != null) {
                for (int i = 0; i < dialogs.size(); i++) {
                    initialAllowedDialogs.add(String.valueOf(dialogs.keyAt(i)));
                }
            }

            if (!initialAllowedDialogs.isEmpty()) {
                prefs.edit().putStringSet(allowedDialogsKey(account), initialAllowedDialogs).apply();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static boolean isDialogAllowed(Context context, int account, long dialogId) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(CHILD_PREFS, Context.MODE_PRIVATE);
            Set<String> allowedDialogs = prefs.getStringSet(allowedDialogsKey(account), null);
            return allowedDialogs != null && allowedDialogs.contains(String.valueOf(dialogId));
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void allowDialog(Context context, int account, long dialogId) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(CHILD_PREFS, Context.MODE_PRIVATE);
            Set<String> current = prefs.getStringSet(allowedDialogsKey(account), null);
            HashSet<String> allowedDialogs = current != null ? new HashSet<>(current) : new HashSet<>();
            allowedDialogs.add(String.valueOf(dialogId));
            prefs.edit().putStringSet(allowedDialogsKey(account), allowedDialogs).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static boolean isDialogShown(GateType gateType) {
        switch (gateType) {
            case SEARCH:
                return searchPasswordDialogShown;
            case JOIN:
                return joinPasswordDialogShown;
            case NEW_DIALOG:
                return newDialogPasswordDialogShown;
        }
        return false;
    }

    private static void setDialogShown(GateType gateType, boolean shown) {
        switch (gateType) {
            case SEARCH:
                searchPasswordDialogShown = shown;
                break;
            case JOIN:
                joinPasswordDialogShown = shown;
                break;
            case NEW_DIALOG:
                newDialogPasswordDialogShown = shown;
                break;
        }
    }
}
