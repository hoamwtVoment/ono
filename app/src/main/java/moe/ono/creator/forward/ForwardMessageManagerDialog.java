package moe.ono.creator.forward;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;

import moe.ono.bridge.ntapi.ChatTypeConstants;
import moe.ono.hooks.base.util.Toasts;
import moe.ono.util.ContactUtils;
import moe.ono.util.Logger;
import moe.ono.util.Session;

/** Programmatic UI so no layout XML/resource ID changes are required. */
public final class ForwardMessageManagerDialog {
    private ForwardMessageManagerDialog() {}

    public static void show(
            Context context,
            ForwardMessageDraft draft,
            String defaultUin,
            String defaultNickname,
            String defaultElements,
            Runnable onChanged
    ) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 16);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(context);
        hint.setText("每一项是一条独立的转发记录。点击编辑，左右滑动删除，上下拖动排序。");
        root.addView(hint);

        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        ForwardAdapter adapter = new ForwardAdapter(draft, () -> {
            if (onChanged != null) onChanged.run();
        });
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 340)
        ));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button add = new Button(context);
        add.setText("添加消息");
        Button clear = new Button(context);
        clear.setText("清空");
        buttons.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle("转发消息管理（" + draft.size() + "）")
                .setView(root)
                .setPositiveButton("完成", null);
        final android.app.Dialog dialog = builder.create();

        add.setOnClickListener(v -> showEditor(
                context,
                null,
                defaultUin,
                defaultNickname,
                defaultElements,
                node -> {
                    draft.add(node);
                    adapter.notifyItemInserted(draft.size() - 1);
                    if (onChanged != null) onChanged.run();
                }
        ));

        clear.setOnClickListener(v -> {
            if (draft.isEmpty()) return;
            new MaterialAlertDialogBuilder(context)
                    .setTitle("清空全部消息？")
                    .setMessage("此操作只清空当前编辑草稿。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空", (d, which) -> {
                        int old = draft.size();
                        draft.clear();
                        adapter.notifyItemRangeRemoved(0, old);
                        if (onChanged != null) onChanged.run();
                    })
                    .show();
        });

        adapter.setOnEdit(position -> showEditor(
                context,
                draft.get(position),
                defaultUin,
                defaultNickname,
                defaultElements,
                node -> {
                    draft.set(position, node);
                    adapter.notifyItemChanged(position);
                    if (onChanged != null) onChanged.run();
                }
        ));

        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT
        ) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from < 0 || to < 0) return false;
                draft.move(from, to);
                adapter.notifyItemMoved(from, to);
                if (onChanged != null) onChanged.run();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position < 0) return;
                draft.remove(position);
                adapter.notifyItemRemoved(position);
                if (onChanged != null) onChanged.run();
            }
        });
        helper.attachToRecyclerView(list);
        dialog.show();
    }

    private interface NodeConsumer { void accept(ForwardMessageNode node); }

    private static void showEditor(
            Context context,
            ForwardMessageNode existing,
            String defaultUin,
            String defaultNickname,
            String defaultElements,
            NodeConsumer consumer
    ) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 16);
        form.setPadding(pad, 0, pad, 0);

        EditText uin = new EditText(context);
        uin.setHint("发送者 UIN");
        uin.setInputType(InputType.TYPE_CLASS_NUMBER);
        uin.setText(existing == null ? defaultUin : String.valueOf(existing.getUin()));
        form.addView(uin);

        EditText nickname = new EditText(context);
        nickname.setHint("显示昵称");
        nickname.setText(existing == null ? defaultNickname : existing.getNickname());
        form.addView(nickname);
        uin.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                nickname.setText(getDisplayNameForUin(s.toString().trim()));
            }
        });

        EditText time = new EditText(context);
        time.setHint("时间戳（秒，留空为当前时间）");
        time.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (existing != null) time.setText(String.valueOf(existing.getTimeSeconds()));
        form.addView(time);

        EditText elements = new EditText(context);
        elements.setHint("Element JSON 数组");
        elements.setMinLines(5);
        elements.setText(existing == null ? defaultElements : existing.getElementsJson());
        form.addView(elements);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(existing == null ? "添加转发消息" : "编辑转发消息")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        long parsedUin = Long.parseLong(uin.getText().toString().trim());
                        String parsedName = nickname.getText().toString().trim();
                        if (parsedName.isEmpty()) parsedName = String.valueOf(parsedUin);
                        String rawElements = elements.getText().toString().trim();
                        long parsedTime = time.getText().toString().trim().isEmpty()
                                ? System.currentTimeMillis() / 1000L
                                : Long.parseLong(time.getText().toString().trim());
                        ForwardMessageNode node = new ForwardMessageNode(
                                parsedUin, parsedName, rawElements, parsedTime
                        );
                        node.parseElements();
                        consumer.accept(node);
                        dialog.dismiss();
                    } catch (NumberFormatException e) {
                        Toasts.error(context, "UIN 或时间戳格式错误");
                    } catch (JSONException e) {
                        Toasts.error(context, "Element JSON 错误：" + e.getMessage());
                    }
                }));
        dialog.show();
    }

    private static String getDisplayNameForUin(String uin) {
        if (uin.isEmpty()) return "";
        try {
            if (Session.getCurrentChatType() == ChatTypeConstants.GROUP) {
                long groupUin = Long.parseLong(Session.getCurrentPeerID());
                return ContactUtils.getDisplayNameForUin(uin, groupUin);
            }
            return ContactUtils.getDisplayNameForUin(uin);
        } catch (RuntimeException e) {
            Logger.e("Failed to resolve display name for UIN " + uin, e);
            return uin;
        }
    }

    private interface EditListener { void edit(int position); }

    private static final class ForwardAdapter extends RecyclerView.Adapter<ForwardViewHolder> {
        private final ForwardMessageDraft draft;
        private final Runnable changed;
        private EditListener editListener;

        private ForwardAdapter(ForwardMessageDraft draft, Runnable changed) {
            this.draft = draft;
            this.changed = changed;
        }

        void setOnEdit(EditListener listener) { this.editListener = listener; }

        @NonNull
        @Override
        public ForwardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
            TextView title = new TextView(context);
            title.setTextSize(16);
            TextView preview = new TextView(context);
            preview.setTextSize(13);
            row.addView(title);
            row.addView(preview);
            return new ForwardViewHolder(row, title, preview);
        }

        @Override
        public void onBindViewHolder(@NonNull ForwardViewHolder holder, int position) {
            ForwardMessageNode node = draft.get(position);
            holder.title.setText((position + 1) + ". " + node.getNickname() + "（" + node.getUin() + "）");
            holder.preview.setText(node.previewText());
            holder.itemView.setOnClickListener(v -> {
                int p = holder.getAdapterPosition();
                if (p >= 0 && editListener != null) editListener.edit(p);
            });
            holder.itemView.setOnLongClickListener(v -> {
                int p = holder.getAdapterPosition();
                if (p < 0) return true;
                draft.remove(p);
                notifyItemRemoved(p);
                if (changed != null) changed.run();
                return true;
            });
        }

        @Override
        public int getItemCount() { return draft.size(); }
    }

    private static final class ForwardViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView preview;
        ForwardViewHolder(@NonNull View itemView, TextView title, TextView preview) {
            super(itemView);
            this.title = title;
            this.preview = preview;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
