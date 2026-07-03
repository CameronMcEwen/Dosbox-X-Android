package com.dosboxx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * In-app file browser. Replaces the system SAF picker so the launcher owns
 * the back stack: inside a subfolder the system Back goes up a parent
 * folder; at the root it dismisses the browser (RESULT_CANCELED). The user
 * can then press Back once more on the launcher to return to the running
 * emulator (the launcher's onResume bounces to SDLActivity when the emu
 * is alive).
 *
 * The browser is launched with an initial path and a mode; it returns the
 * picked file/folder via setResult(Activity.RESULT_OK, {EXTRA_RESULT_PATH}).
 *
 * Modes:
 *   MODE_PICK_FILE     — user can pick a file; folders can be drilled into
 *   MODE_PICK_FOLDER   — user must pick a folder; files are dimmed
 *   MODE_PICK_FILE_OR_FOLDER — either is valid
 */
public class InAppFileBrowser extends Activity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_INITIAL_PATH = "initialPath";
    public static final String EXTRA_MODE = "mode";
    /** Comma-separated lower-case extensions to keep in the file list (no
     *  leading dot, e.g. "iso,bin,img,zip"). Folders are always shown.
     *  Empty/null = show all files. */
    public static final String EXTRA_ALLOWED_EXTENSIONS = "allowedExtensions";

    public static final int MODE_PICK_FILE = 1;
    public static final int MODE_PICK_FOLDER = 2;
    public static final int MODE_PICK_FILE_OR_FOLDER = 3;

    public static final String EXTRA_RESULT_PATH = "resultPath";

    private int mMode;
    private File mCurrent;
    private final List<File> mRoots = new ArrayList<>();
    private java.util.Set<String> mAllowedExt = new java.util.HashSet<>();
    private ListView mList;
    private TextView mPathView;
    private Button mPickHereBtn;
    private TextView mEmptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle b = getIntent() != null ? getIntent().getExtras() : null;
        String title = b != null ? b.getString(EXTRA_TITLE, "Choose") : "Choose";
        mMode = b != null ? b.getInt(EXTRA_MODE, MODE_PICK_FILE_OR_FOLDER)
                          : MODE_PICK_FILE_OR_FOLDER;
        String initialPath = b != null ? b.getString(EXTRA_INITIAL_PATH, null) : null;
        if (initialPath != null) mCurrent = new File(initialPath);
        if (mCurrent == null || !mCurrent.isDirectory()) mCurrent = defaultStart();
        String exts = b != null ? b.getString(EXTRA_ALLOWED_EXTENSIONS, null) : null;
        if (exts != null) {
            for (String e : exts.split(",")) {
                String t = e.trim().toLowerCase(java.util.Locale.US);
                if (!t.isEmpty()) mAllowedExt.add(t);
            }
        }
        collectRoots();

        // --- UI ---
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        final int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        // Handle system bars (status bar, navigation bar, cutouts) on modern Android.
        // Target SDK 35+ enforces edge-to-edge, which draws behind these bars.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad + insets.getSystemWindowInsetLeft(),
                         pad + insets.getSystemWindowInsetTop(),
                         pad + insets.getSystemWindowInsetRight(),
                         pad + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(18);
        root.addView(titleView);

        mPathView = new TextView(this);
        mPathView.setTextColor(0xFF9FB6CC);
        mPathView.setTextSize(12);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(4);
        root.addView(mPathView, plp);

        mList = new ListView(this);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.topMargin = dp(8);
        root.addView(mList, llp);

        mEmptyView = new TextView(this);
        mEmptyView.setText("This folder is empty.");
        mEmptyView.setTextColor(0xFF7A8A99);
        mEmptyView.setTextSize(14);
        mEmptyView.setPadding(0, dp(24), 0, 0);
        mEmptyView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        mList.setEmptyView(mEmptyView);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(mEmptyView, elp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        root.addView(buttons, blp);

        mPickHereBtn = new Button(this);
        mPickHereBtn.setAllCaps(false);
        mPickHereBtn.setOnClickListener(v -> pickHere());
        buttons.addView(mPickHereBtn, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        buttons.addView(cancel, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        setContentView(root);
        refresh();
    }

    /** Start in the user-configured base folder (the same dir used by
     *  games/, cds/, import/). Falls back to the app's standard external
     *  files dir if AppConfig can't resolve a directory. */
    private File defaultStart() {
        File base = AppConfig.baseDir(this);
        if (base != null && base.isDirectory()) return base;
        File ext = getExternalFilesDir(null);
        if (ext != null && ext.isDirectory()) return ext;
        return getFilesDir();
    }

    private void collectRoots() {
        // The browser is rooted at the user-configured base folder (the
        // parent folder for games/, cds/, import/, keymaps/, gamemeta/).
        // Device storage is reachable from there via a single "Browse
        // device storage…" row when the user actually needs it.
        addRoot(AppConfig.baseDir(this));
    }

    private void addRoot(File f) {
        if (f == null || !f.isDirectory()) return;
        for (File r : mRoots) if (r.getAbsolutePath().equals(f.getAbsolutePath())) return;
        mRoots.add(f);
    }

    private boolean isAtRoot() {
        if (mCurrent == null) return true;
        for (File r : mRoots) {
            if (r.getAbsolutePath().equals(mCurrent.getAbsolutePath())) return true;
        }
        return false;
    }

    /** True when the user is on the configured base folder (the only root). */
    private boolean atParentRoot() {
        if (mCurrent == null) return false;
        File parent = AppConfig.baseDir(this);
        return parent != null
            && parent.getAbsolutePath().equals(mCurrent.getAbsolutePath());
    }

    /** Show the "Browse device storage…" row only when the picker mode
     *  makes it useful (picking a file or folder from outside the
     *  parent folder). In a future non-pick mode the row stays hidden. */
    private boolean pickModeAllowsDeviceBrowse() {
        return mMode == MODE_PICK_FILE || mMode == MODE_PICK_FOLDER
            || mMode == MODE_PICK_FILE_OR_FOLDER;
    }

    /** Back behaviour: at a subfolder, go to the parent; at a root, cancel
     *  the picker so the launcher can decide what to do next (e.g. bounce
     *  the user back to a running emulator). */
    @Override
    public void onBackPressed() {
        if (!isAtRoot() && mCurrent != null && mCurrent.getParentFile() != null
                && mCurrent.getParentFile().isDirectory()
                && mCurrent.getParentFile().canRead()) {
            mCurrent = mCurrent.getParentFile();
            refresh();
            return;
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    private void refresh() {
        mPathView.setText(mCurrent != null ? mCurrent.getAbsolutePath() : "");
        mPickHereBtn.setEnabled(mCurrent != null && mMode != MODE_PICK_FILE);
        if (mMode == MODE_PICK_FILE)      mPickHereBtn.setText("Pick this file");
        else if (mMode == MODE_PICK_FOLDER) mPickHereBtn.setText("Pick this folder");
        else                              mPickHereBtn.setText("Pick this");

        // Build the list in a fixed, predictable order:
        //   1) Parent (always first, hidden at a root)
        //   2) Folders in the current directory
        //   3) Files in the current directory (filtered by extension)
        //   4) At the parent-folder root only, a single "Browse device
        //      storage…" row at the bottom — opt-in access to /storage.
        final List<Row> rows = new ArrayList<>();
        if (!isAtRoot() && mCurrent.getParentFile() != null
                && mCurrent.getParentFile().isDirectory()) {
            rows.add(new Row(Row.KIND_PARENT, mCurrent.getParentFile(), "..  (parent folder)"));
        }
        File[] kids = mCurrent.listFiles();
        List<File> folders = new ArrayList<>();
        List<File> files = new ArrayList<>();
        final boolean hideSystemNoise = mMode == MODE_PICK_FOLDER && isDeviceRoot(mCurrent);
        if (kids != null) {
            for (File f : kids) {
                if (isHiddenName(f.getName())) continue;
                if (f.isDirectory()) {
                    if (hideSystemNoise && isSystemNoiseFolder(f.getName())) continue;
                    folders.add(f);
                } else if (fileAllowed(f)) {
                    files.add(f);
                }
            }
        }
        Collections.sort(folders, NAME_CI);
        Collections.sort(files, NAME_CI);
        for (File f : folders) rows.add(new Row(Row.KIND_FOLDER, f, f.getName() + "/"));
        for (File f : files)   rows.add(new Row(Row.KIND_FILE, f, f.getName()));
        if (atParentRoot() && pickModeAllowsDeviceBrowse()) {
            File dev = Environment.getExternalStorageDirectory();
            if (dev != null && dev.isDirectory()
                    && !dev.getAbsolutePath().equals(mCurrent.getAbsolutePath())) {
                rows.add(new Row(Row.KIND_BROWSE_DEVICE, dev,
                    "📂  Browse device storage…  (" + dev.getAbsolutePath() + ")"));
            }
        }
        boolean noRealRows = folders.isEmpty() && files.isEmpty()
            && (isAtRoot() || mCurrent.getParentFile() == null);
        mEmptyView.setText(noRealRows
            ? emptyStateMessage(hideSystemNoise)
            : "");
        mEmptyView.setVisibility(noRealRows ? View.VISIBLE : View.GONE);
        ArrayAdapter<Row> ad = new ArrayAdapter<Row>(this,
                android.R.layout.simple_list_item_1, rows) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, cv, parent);
                Row r = rows.get(pos);
                v.setText(r.label);
                switch (r.kind) {
                    case Row.KIND_PARENT: v.setTextColor(0xFF9FB6CC); break;
                    case Row.KIND_HEADER: v.setTextColor(0xFF7A8A99); break;
                    case Row.KIND_ROOT:   v.setTextColor(0xFF66CC66); break;
                    case Row.KIND_BROWSE_DEVICE: v.setTextColor(0xFF66CC66); break;
                    case Row.KIND_FOLDER: v.setTextColor(0xFFE0E0E0); break;
                    case Row.KIND_FILE:
                        v.setTextColor(mMode == MODE_PICK_FOLDER ? 0xFF556677 : 0xFFC0C8D0);
                        break;
                }
                return v;
            }
        };
        mList.setAdapter(ad);
        mList.setOnItemClickListener((parent, view, position, id) -> {
            Row r = rows.get(position);
            switch (r.kind) {
                case Row.KIND_HEADER:
                    break;
                case Row.KIND_PARENT:
                case Row.KIND_ROOT:
                case Row.KIND_BROWSE_DEVICE:
                case Row.KIND_FOLDER:
                    mCurrent = r.file;
                    refresh();
                    break;
                case Row.KIND_FILE:
                    if (mMode == MODE_PICK_FOLDER) {
                        Toast.makeText(this, "Pick a folder, not a file.", Toast.LENGTH_SHORT).show();
                    } else {
                        returnResult(r.file);
                    }
                    break;
            }
        });
    }

    private boolean fileAllowed(File f) {
        if (mAllowedExt.isEmpty()) return true;
        String n = f.getName().toLowerCase(java.util.Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        return mAllowedExt.contains(n.substring(dot + 1));
    }

    /** Hidden / dot-prefixed items: .thumbnails, .trash, macOS ._foo, etc. */
    private static boolean isHiddenName(String name) {
        return name != null && !name.isEmpty() && name.charAt(0) == '.';
    }

    /** A device-storage root: /storage/emulated/0 or /storage/<vol-id>/. */
    private static boolean isDeviceRoot(File f) {
        if (f == null) return false;
        String p = f.getAbsolutePath();
        if (p.equals("/storage/emulated/0")) return true;
        if (p.startsWith("/storage/") && p.indexOf('/', "/storage/".length()) < 0) return true;
        return false;
    }

    /** Stock Android / FAT32 system folders that aren't useful for picking a
     *  game folder — hidden on a device-storage root in folder-pick mode. */
    private static boolean isSystemNoiseFolder(String name) {
        switch (name) {
            case "Android": case "DCIM": case "Pictures": case "Music":
            case "Movies": case "Video": case "Download": case "Documents":
            case "Notifications": case "Podcasts": case "Ringtones":
            case "Alarms": case "LOST.DIR":
                return true;
            default:
                return false;
        }
    }

    private String emptyStateMessage(boolean hideSystemNoise) {
        if (mAllowedExt.isEmpty()) {
            return hideSystemNoise
                ? "No game folders here yet. Create a new folder for your game, or pick a folder elsewhere."
                : "This folder is empty.";
        }
        return "This folder has no files with extensions: " + extSummary() + ".";
    }

    private String extSummary() {
        List<String> xs = new java.util.ArrayList<>(mAllowedExt);
        Collections.sort(xs);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('.').append(xs.get(i));
        }
        return sb.toString();
    }

    private static final Comparator<File> NAME_CI = new Comparator<File>() {
        @Override public int compare(File a, File b) {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };

    private void pickHere() {
        if (mCurrent == null) return;
        if (mMode == MODE_PICK_FILE) {
            Toast.makeText(this, "This is a folder — open it and pick a file.", Toast.LENGTH_SHORT).show();
            return;
        }
        returnResult(mCurrent);
    }

    private void returnResult(File f) {
        Intent out = new Intent();
        out.putExtra(EXTRA_RESULT_PATH, f.getAbsolutePath());
        setResult(RESULT_OK, out);
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static class Row {
        static final int KIND_PARENT = 0;
        static final int KIND_HEADER = 1;
        static final int KIND_ROOT   = 2;
        static final int KIND_FOLDER = 3;
        static final int KIND_FILE   = 4;
        static final int KIND_BROWSE_DEVICE = 5;
        final int kind;
        final File file;
        final String label;
        Row(int kind, File file, String label) { this.kind = kind; this.file = file; this.label = label; }
    }
}
