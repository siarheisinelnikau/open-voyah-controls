package app.voyahmusic.transfer.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import app.voyahmusic.shared.lan.MusicWire.Folder;
import app.voyahmusic.shared.lan.PairQr;
import app.voyahmusic.shared.ui.MusicActivity;
import app.voyahmusic.shared.ui.UiKit;
import app.voyahmusic.transfer.data.SelectedFile;

import java.util.ArrayList;
import java.util.List;

import static app.voyahmusic.shared.ui.UiKit.MATCH;
import static app.voyahmusic.shared.ui.UiKit.WRAP;

public final class TransferActivity extends MusicActivity {
    private static final int PICK_AUDIO = 612;
    private static final int SCAN_QR = 615;
    private static final int CAMERA = 616;
    private TransferViewModel model;
    private EditText address, code;
    private String lastAddress = "", lastCode = "";
    private LinearLayout fileRows, setup, manualForm;
    private TextView status, connection, connectionDetails;
    private Button choose, send, cancel, destination, connectionAction, forgetCar, scan, manualToggle, pair;
    private ProgressBar progress;
    private List<SelectedFile> renderedFiles;
    private boolean renderedBusy;
    private boolean manual;
    private TransferViewModel.Screen current;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Object retained = getLastNonConfigurationInstance();
        model = retained instanceof TransferViewModel viewModel ? viewModel : new TransferViewModel(this);
        lastAddress = saved == null ? getPreferences(MODE_PRIVATE).getString("car_address", "")
                : saved.getString("car_address", "");
        lastCode = saved == null ? "" : saved.getString("pairing_code", "");
        manual = saved != null && saved.getBoolean("manual");
        renderRoute();
        if (saved == null) handlePairLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairLink(intent);
    }

    private void handlePairLink(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            PairQr qr = PairQr.parse(intent.getDataString());
            if (qr != null) {
                if (settings) onBackPressed();
                model.pair(qr.host(), qr.fingerprint());
            }
        }
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
        output.putBoolean("manual", manual);
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

        LinearLayout bar = ui.card(12);
        LinearLayout barRow = ui.row();
        LinearLayout labels = ui.column();
        connection = ui.text("", 17, UiKit.TEXT, true);
        connection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connectionDetails = ui.text("", 14, UiKit.MUTED, false);
        ui.add(labels, connection, MATCH, WRAP, 0);
        ui.add(labels, connectionDetails, MATCH, WRAP, 5);
        barRow.addView(labels, ui.weighted());
        ui.gap(barRow, 8);
        connectionAction = ui.button("", false, this::connectionAction);
        barRow.addView(connectionAction, WRAP, WRAP);
        ui.add(bar, barRow, MATCH, WRAP, 0);
        ui.add(root, bar, MATCH, WRAP, 10);

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

        setup = ui.card(16);
        ui.add(content, setup, MATCH, WRAP, 0);
        TextView instructions = ui.text(getText(R.string.transfer_instructions), 15, UiKit.MUTED, false);
        instructions.setLineSpacing(ui.dp(3), 1f);
        ui.add(setup, instructions, MATCH, WRAP, 0);
        scan = ui.button(getText(R.string.scan_qr), true, this::scan);
        ui.add(setup, scan, MATCH, WRAP, 12);
        manualToggle = ui.button(getText(R.string.manual_pair), false, () -> {
            manual = !manual;
            updateConnection(current);
        });
        ui.add(setup, manualToggle, MATCH, WRAP, 8);
        manualForm = ui.column();
        ui.add(setup, manualForm, MATCH, WRAP, 0);
        address = input(lastAddress, R.string.address_example, false);
        addInput(manualForm, address, R.string.car_address, 14);
        code = input(lastCode, R.string.code_hint, true);
        addInput(manualForm, code, R.string.pairing_code, 12);
        pair = ui.button(getText(R.string.connect_car), true, this::pair);
        ui.add(manualForm, pair, MATCH, WRAP, 10);

        forgetCar = ui.button(getText(R.string.forget_car), false, this::forget);
        ui.add(content, forgetCar, MATCH, WRAP, 0);

        LinearLayout files = ui.card(16);
        ui.add(content, files, MATCH, WRAP, 12);
        section(files, UiKit.Icon.NOTE, R.string.files);
        destination = ui.button(getText(R.string.library_root), false, this::chooseFolder);
        ui.add(files, destination, MATCH, WRAP, 9);
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
        if (request == SCAN_QR && result == RESULT_OK && data != null) {
            PairQr invite = PairQr.parse(data.getStringExtra(ScanActivity.QR_RESULT));
            if (invite != null) model.pair(invite.host(), invite.fingerprint());
            return;
        }
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

    private void pair() {
        saveConnection();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(code.getWindowToken(), 0);
        model.pair(lastAddress, lastCode);
    }

    private void send() {
        model.send();
    }

    private void scan() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show();
        } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA);
        }
    }

    @SuppressWarnings("deprecation")
    private void launchScanner() {
        startActivityForResult(new Intent(this, ScanActivity.class), SCAN_QR);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) launchScanner();
        else Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show();
    }

    private void connectionAction() {
        if (current == null) return;
        switch (current.connection().state()) {
            case PAIRING -> model.cancel();
            case CONNECTED, CHECKING -> model.disconnect();
            case DISCONNECTED, OFFLINE -> model.reconnect();
            case UNPAIRED -> scan();
        }
    }

    private void forget() {
        new AlertDialog.Builder(this).setMessage(R.string.forget_car_prompt)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget_car, (dialog, which) -> model.forget()).show();
    }

    private void chooseFolder() {
        if (current == null) return;
        List<Folder> choices = new ArrayList<>();
        choices.add(new Folder(0, getString(R.string.library_root)));
        choices.addAll(current.connection().folders());
        int selected = 0;
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id() == current.connection().destination()) selected = i;
        }
        CharSequence[] names = choices.stream().map(Folder::name).toArray(CharSequence[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.destination)
                .setSingleChoiceItems(names, selected, (dialog, index) -> {
                    model.target(choices.get(index).id());
                    dialog.dismiss();
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void render(TransferViewModel.Screen screen) {
        current = screen;
        if (screen.busy()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (settings || fileRows == null) return;
        updateConnection(screen);
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
        ui.enabled(send, !busy && !screen.files().isEmpty() && screen.connection().state() == TransferViewModel.Link.CONNECTED);
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

    private void updateConnection(TransferViewModel.Screen screen) {
        if (screen == null) return;
        TransferViewModel.Connection link = screen.connection();
        int title = switch (link.state()) {
            case UNPAIRED -> R.string.no_car;
            case PAIRING -> R.string.waiting_approval;
            case CHECKING -> R.string.connecting;
            case CONNECTED -> R.string.car_connected;
            case OFFLINE -> R.string.car_offline;
            case DISCONNECTED -> R.string.car_disconnected;
        };
        set(connection, getString(title));
        connection.setTextColor(link.state() == TransferViewModel.Link.CONNECTED ? ui.accent : UiKit.TEXT);
        String detail = link.state() == TransferViewModel.Link.PAIRING
                ? getString(R.string.match_confirmation, link.confirmation()) : link.address();
        set(connectionDetails, detail);
        connectionDetails.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        connectionAction.setVisibility(link.state() == TransferViewModel.Link.UNPAIRED ? View.GONE : View.VISIBLE);
        int action = switch (link.state()) {
            case UNPAIRED -> R.string.scan_qr;
            case PAIRING -> R.string.cancel;
            case CONNECTED, CHECKING -> R.string.disconnect;
            case OFFLINE, DISCONNECTED -> R.string.connect_car;
        };
        if (!getText(action).equals(connectionAction.getText())) connectionAction.setText(action);
        setup.setVisibility(link.state() == TransferViewModel.Link.UNPAIRED ? View.VISIBLE : View.GONE);
        manualForm.setVisibility(manual ? View.VISIBLE : View.GONE);
        manualToggle.setText(manual ? R.string.hide_manual : R.string.manual_pair);
        forgetCar.setVisibility(link.state() == TransferViewModel.Link.OFFLINE
                || link.state() == TransferViewModel.Link.DISCONNECTED ? View.VISIBLE : View.GONE);
        destination.setVisibility(link.state() == TransferViewModel.Link.CONNECTED ? View.VISIBLE : View.GONE);
        String target = getString(R.string.library_root);
        for (Folder folder : link.folders()) {
            if (folder.id() == link.destination()) target = folder.name();
        }
        set(destination, getString(R.string.destination_value, target));
        ui.enabled(destination, !screen.busy());
    }

    private static void set(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
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
                    case DECLINED -> R.string.pair_denied;
                    case UNPAIRED -> R.string.car_forgot_phone;
                    case FOLDER -> R.string.folder_unavailable;
                    case BUSY -> R.string.car_busy;
                    default -> R.string.transfer_error;
                };
                String name = item.current() == null ? "" : item.current().name() + "\n";
                yield name + getString(error);
            }
        };
    }
}
