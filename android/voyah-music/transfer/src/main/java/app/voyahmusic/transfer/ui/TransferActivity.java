package app.voyahmusic.transfer.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.voyahmusic.shared.R;
import app.voyahmusic.shared.ui.MusicActivity;
import app.voyahmusic.shared.ui.UiKit;
import app.voyahmusic.transfer.data.SelectedFile;

import java.util.ArrayList;
import java.util.List;

import static app.voyahmusic.shared.ui.UiKit.MATCH;
import static app.voyahmusic.shared.ui.UiKit.WRAP;

public final class TransferActivity extends MusicActivity {
    private static final int PICK_AUDIO = 612;
    private TransferViewModel model;
    private EditText address, code;
    private String lastAddress = "", lastCode = "";
    private LinearLayout fileRows;
    private TextView status;
    private Button choose, send, cancel;
    private ProgressBar progress;
    private List<SelectedFile> renderedFiles;
    private boolean renderedBusy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Object retained = getLastNonConfigurationInstance();
        model = retained instanceof TransferViewModel viewModel ? viewModel : new TransferViewModel(this);
        lastAddress = saved == null ? getPreferences(MODE_PRIVATE).getString("car_address", "")
                : saved.getString("car_address", "");
        lastCode = saved == null ? "" : saved.getString("pairing_code", "");
        renderRoute();
    }

    @Override
    protected void onStart() {
        super.onStart();
        model.observe(this::render);
    }

    @Override
    protected void onStop() {
        model.observe(null);
        super.onStop();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return model;
    }

    @Override
    protected void onDestroy() {
        if (!isChangingConfigurations()) model.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle output) {
        saveConnection();
        output.putString("car_address", lastAddress);
        output.putString("pairing_code", lastCode);
        super.onSaveInstanceState(output);
    }

    @Override
    protected void openSettings() {
        saveConnection();
        super.openSettings();
    }

    private void saveConnection() {
        if (address == null || code == null) return;
        lastAddress = address.getText().toString().trim();
        lastCode = code.getText().toString();
    }

    @Override
    protected void showMain() {
        LinearLayout root = page();
        LinearLayout header = heading(getString(R.string.transfer_name), false);
        settingsButton(header);
        ui.add(root, header, MATCH, WRAP, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        scrollParams.topMargin = ui.dp(12);
        root.addView(scroll, scrollParams);
        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame, MATCH, WRAP);
        LinearLayout content = ui.column();
        int width = getResources().getConfiguration().screenWidthDp;
        frame.addView(content, new FrameLayout.LayoutParams(width >= 616 ? ui.dp(560) : MATCH, WRAP, Gravity.CENTER_HORIZONTAL));
        content.setPadding(0, 0, 0, ui.dp(8));

        LinearLayout connection = ui.card(16);
        ui.add(content, connection, MATCH, WRAP, 0);
        section(connection, UiKit.Icon.SEND, R.string.transfer_connection);
        TextView instructions = ui.text(getText(R.string.transfer_instructions), 15, UiKit.MUTED, false);
        instructions.setLineSpacing(ui.dp(3), 1f);
        ui.add(connection, instructions, MATCH, WRAP, 13);
        address = input(lastAddress, R.string.address_example, false);
        addInput(connection, address, R.string.car_address, 18);
        code = input(lastCode, R.string.code_hint, true);
        addInput(connection, code, R.string.pairing_code, 14);

        LinearLayout files = ui.card(16);
        ui.add(content, files, MATCH, WRAP, 12);
        section(files, UiKit.Icon.NOTE, R.string.files);
        fileRows = ui.column();
        ui.add(files, fileRows, MATCH, WRAP, 8);
        choose = ui.button(getText(R.string.add_files), false, this::pick);
        ui.add(files, choose, MATCH, WRAP, 8);

        LinearLayout footer = ui.column();
        status = ui.text("", 15, UiKit.MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.add(footer, status, MATCH, WRAP, 3);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(ui.accent));
        progress.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
        ui.add(footer, progress, MATCH, ui.dp(8), 7);
        LinearLayout buttons = ui.row();
        cancel = ui.button(getText(R.string.cancel), false, model::cancel);
        send = ui.button(getText(R.string.send_files), true, this::send);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(WRAP, ui.dp(52));
        cancelParams.rightMargin = ui.dp(8);
        buttons.addView(cancel, cancelParams);
        buttons.addView(send, new LinearLayout.LayoutParams(0, ui.dp(52), 1));
        ui.add(footer, buttons, MATCH, WRAP, 8);
        FrameLayout footerFrame = new FrameLayout(this);
        footerFrame.addView(footer, new FrameLayout.LayoutParams(width >= 616 ? ui.dp(560) : MATCH,
                WRAP, Gravity.CENTER_HORIZONTAL));
        ui.add(root, footerFrame, MATCH, WRAP, 0);
        setContentView(root);
        root.requestFocus();
        renderedFiles = null;
        model.observe(this::render);
    }

    private void section(LinearLayout card, UiKit.Icon icon, int label) {
        LinearLayout heading = ui.row();
        heading.addView(ui.mark(icon, 22), ui.dp(24), ui.dp(24));
        ui.gap(heading, 9);
        heading.addView(ui.text(getText(label), 20, UiKit.TEXT, true), WRAP, WRAP);
        ui.add(card, heading, MATCH, WRAP, 0);
    }

    private EditText input(String value, int hint, boolean pin) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(17);
        field.setTextColor(UiKit.TEXT);
        field.setHintTextColor(UiKit.MUTED);
        field.setHint(hint);
        int keyboard = InputType.TYPE_CLASS_NUMBER | (pin ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setInputType(keyboard);
        field.setKeyListener(DigitsKeyListener.getInstance(pin ? "0123456789" : "0123456789."));
        field.setRawInputType(keyboard);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(pin ? 8 : 15)});
        field.setBackground(ui.box(UiKit.SURFACE, ui.edge, 12));
        field.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        field.setMinHeight(ui.dp(49));
        field.setText(value);
        field.setId(View.generateViewId());
        return field;
    }

    private void addInput(LinearLayout card, EditText field, int label, int top) {
        TextView caption = ui.text(getText(label), 14, UiKit.TEXT, true);
        caption.setLabelFor(field.getId());
        ui.add(card, caption, MATCH, WRAP, top);
        ui.add(card, field, MATCH, WRAP, 7);
    }

    @SuppressWarnings("deprecation")
    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[] {"audio/*", "application/ogg", "application/octet-stream"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, PICK_AUDIO);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_AUDIO || result != RESULT_OK || data == null) return;
        List<Uri> files = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) files.add(uri);
            }
        } else if (data.getData() != null) {
            files.add(data.getData());
        }
        if (!files.isEmpty()) model.choose(files);
    }

    private void send() {
        saveConnection();
        getPreferences(MODE_PRIVATE).edit().putString("car_address", lastAddress).apply();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(code.getWindowToken(), 0);
        model.send(lastAddress, lastCode);
    }

    private void render(TransferViewModel.Screen screen) {
        if (screen.busy()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (settings || fileRows == null) return;
        boolean busy = screen.busy();
        boolean sending = screen.state() == TransferViewModel.State.SENDING;
        if (renderedFiles != screen.files() || renderedBusy != busy) {
            renderedFiles = screen.files();
            renderedBusy = busy;
            fileRows.removeAllViews();
            for (SelectedFile file : renderedFiles) addFile(file, busy);
            boolean empty = screen.files().isEmpty();
            ui.styleButton(choose, empty);
            choose.setText(empty ? R.string.add_files : R.string.add_more);
        }
        ui.enabled(choose, !busy);
        ui.enabled(send, !busy && !screen.files().isEmpty());
        address.setEnabled(!busy);
        code.setEnabled(!busy);
        cancel.setVisibility(sending || screen.state() == TransferViewModel.State.CONNECTING ? View.VISIBLE : View.GONE);
        boolean unknown = !sending || screen.current().size() <= 0;
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            if (progress.isIndeterminate() != unknown) progress.setIndeterminate(unknown);
            if (!unknown) progress.setProgress((int) Math.min(100, 100L * screen.bytes() / screen.current().size()));
        }
        String label = message(screen);
        if (!label.contentEquals(status.getText())) status.setText(label);
        status.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
        boolean failed = switch (screen.state()) {
            case NO_WIFI, ADDRESS, CODE, CONNECTION, FAILED -> true;
            default -> false;
        };
        status.setTextColor(failed ? UiKit.ERROR : screen.state() == TransferViewModel.State.DONE ? ui.accent : UiKit.MUTED);
    }

    private void addFile(SelectedFile file, boolean busy) {
        LinearLayout row = ui.row();
        row.setBackground(ui.box(UiKit.SURFACE, ui.edge, 12));
        row.setPadding(ui.dp(10), ui.dp(5), ui.dp(5), ui.dp(5));
        LinearLayout labels = ui.column();
        TextView name = ui.text(file.name(), 15, UiKit.TEXT, true);
        ui.truncate(name, 2);
        ui.add(labels, name, MATCH, WRAP, 0);
        if (file.size() >= 0) {
            ui.add(labels, ui.text(Formatter.formatShortFileSize(this, file.size()), 13, UiKit.MUTED, false), MATCH, WRAP, 4);
        }
        row.addView(labels, ui.weighted());
        ui.gap(row, 6);
        View remove = ui.iconButton(UiKit.Icon.CLOSE, getString(R.string.remove_file, file.name()), false,
                () -> model.remove(file));
        ui.enabled(remove, !busy);
        row.addView(remove, ui.dp(48), ui.dp(48));
        ui.add(fileRows, row, MATCH, WRAP, 6);
    }

    private String message(TransferViewModel.Screen item) {
        return switch (item.state()) {
            case READY -> "";
            case READING -> getString(R.string.reading_files);
            case CONNECTING -> getString(R.string.connecting);
            case SENDING -> getString(R.string.sending, item.completed() + 1, item.completed() + item.files().size(),
                    item.current().name());
            case DONE -> getString(R.string.sent_summary, item.completed() - item.duplicates(), item.duplicates());
            case CANCELLED -> getString(R.string.cancelled);
            case NO_WIFI -> getString(R.string.phone_no_wifi);
            case ADDRESS -> getString(R.string.bad_address);
            case CODE -> getString(R.string.bad_code);
            case CONNECTION -> getString(R.string.connection_lost);
            case FAILED -> {
                int error = switch (item.error()) {
                    case CODE -> R.string.wrong_code;
                    case FORMAT -> R.string.format_error;
                    case TOO_LARGE -> R.string.size_error;
                    case STORAGE -> R.string.storage_error;
                    case EXPIRED -> R.string.session_expired;
                    default -> R.string.transfer_error;
                };
                String name = item.current() == null ? "" : item.current().name() + "\n";
                yield name + getString(error);
            }
        };
    }
}
