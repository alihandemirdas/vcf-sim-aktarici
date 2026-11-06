package com.katreo.vcfsim;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_PERM = 2;
    private static final int GREEN = 0xFF2E7D32;
    private static final int RED = 0xFFC62828;
    private static final int ORANGE = 0xFFE65100;
    private static final int GRAY = 0xFF666666;
    private static final Locale TR = new Locale("tr", "TR");

    static final class Entry {
        String name;
        String number;
        String label;
        String key;
        int phoneIndex;
        int phoneCount;
        boolean checked;
        boolean onSim;
    }

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> visible = new ArrayList<>();
    private final List<SimStore.Sim> sims = new ArrayList<>();
    private final Set<String> simKeys = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SimStore store;
    private SimStore.Info simInfo;
    private int simGeneration;

    private TextView fileText;
    private TextView simText;
    private TextView counterText;
    private Spinner simSpinner;
    private CheckBox trBox;
    private EditText search;
    private Button writeButton;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        askPermissions();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) loadVcf(uri);
    }

    // ---------------- Arayüz ----------------

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.BLACK);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button() {
        Button b = new Button(this);
        b.setAllCaps(false);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(Color.WHITE);
        root.setFocusableInTouchMode(true);

        root.addView(label("1) VCF dosyası", 15, true));
        Button pick = button();
        pick.setText("Dosyalardan VCF seç");
        pick.setOnClickListener(v -> pickFile());
        root.addView(pick);
        fileText = label("Henüz dosya seçilmedi.", 13, false);
        fileText.setTextColor(GRAY);
        root.addView(fileText);

        TextView simTitle = label("2) Hedef SIM", 15, true);
        simTitle.setPadding(0, dp(12), 0, 0);
        root.addView(simTitle);
        simSpinner = new Spinner(this);
        root.addView(simSpinner);
        simText = label("SIM'ler okunuyor…", 13, false);
        simText.setTextColor(GRAY);
        root.addView(simText);

        trBox = new CheckBox(this);
        trBox.setText("Türkçe harfleri sadeleştir (ş→s, ğ→g, ı→i) — isimler SIM'e daha uzun sığar");
        trBox.setTextSize(13);
        trBox.setChecked(true);
        trBox.setOnCheckedChangeListener((b, c) -> adapter.notifyDataSetChanged());
        root.addView(trBox);

        TextView listTitle = label("3) Aktarılacak kişileri seç", 15, true);
        listTitle.setPadding(0, dp(8), 0, 0);
        root.addView(listTitle);

        search = new EditText(this);
        search.setHint("Ara (isim ya da numara)");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        root.addView(search);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button selAll = button();
        selAll.setText("Tümünü seç");
        selAll.setOnClickListener(v -> {
            for (Entry e : visible) if (!e.onSim) e.checked = true;
            refreshList();
        });
        Button selNone = button();
        selNone.setText("Temizle");
        selNone.setOnClickListener(v -> {
            for (Entry e : visible) e.checked = false;
            refreshList();
        });
        counterText = label("", 13, true);
        counterText.setPadding(dp(8), 0, 0, 0);
        bar.addView(selAll);
        bar.addView(selNone);
        bar.addView(counterText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            Entry e = visible.get(pos);
            e.checked = !e.checked;
            refreshList();
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        writeButton = button();
        writeButton.setTextSize(16);
        writeButton.setTypeface(Typeface.DEFAULT_BOLD);
        writeButton.setTextColor(Color.WHITE);
        writeButton.setBackgroundColor(GREEN);
        writeButton.setOnClickListener(v -> startWrite());
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        wl.topMargin = dp(6);
        root.addView(writeButton, wl);

        setContentView(root);
        updateCounter();
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() {
            return visible.size();
        }

        public Object getItem(int p) {
            return visible.get(p);
        }

        public long getItemId(int p) {
            return p;
        }

        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                h = new Holder();
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));
                h.check = new CheckBox(MainActivity.this);
                h.check.setFocusable(false);
                h.check.setClickable(false);
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                h.name = label("", 16, true);
                h.number = label("", 14, false);
                h.note = label("", 12, false);
                texts.addView(h.name);
                texts.addView(h.number);
                texts.addView(h.note);
                row.addView(h.check);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(h);
                cv = row;
            } else {
                h = (Holder) cv.getTag();
            }
            Entry e = visible.get(pos);
            h.check.setChecked(e.checked);
            h.name.setText(e.name.isEmpty() ? "(isimsiz)" : e.name);
            h.number.setText(e.label.isEmpty() ? e.number : e.number + "  ·  " + e.label);
            if (e.onSim) {
                h.note.setText("Bu numara zaten SIM'de");
                h.note.setTextColor(ORANGE);
            } else {
                h.note.setText("SIM'e şöyle yazılacak: " + simName(e, trBox.isChecked()));
                h.note.setTextColor(GRAY);
            }
            return cv;
        }
    }

    private static final class Holder {
        CheckBox check;
        TextView name;
        TextView number;
        TextView note;
    }

    private void refreshList() {
        adapter.notifyDataSetChanged();
        updateCounter();
    }

    private int selectedCount() {
        int n = 0;
        for (Entry e : all) if (e.checked) n++;
        return n;
    }

    private int freeSlots() {
        if (simInfo == null || simInfo.max <= 0) return -1;
        return Math.max(0, simInfo.max - simInfo.used);
    }

    private void updateCounter() {
        int sel = selectedCount();
        int free = freeSlots();
        counterText.setText(free >= 0 ? "Seçili: " + sel + " / boş yer: " + free : "Seçili: " + sel);
        counterText.setTextColor(free >= 0 && sel > free ? RED : Color.BLACK);
        writeButton.setText("SIM'e yaz (" + sel + " kişi)");
        boolean ok = sel > 0 && store != null;
        writeButton.setEnabled(ok);
        writeButton.setAlpha(ok ? 1f : 0.45f);
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(TR);
        String qDigits = q.replaceAll("[^0-9]", "");
        visible.clear();
        for (Entry e : all) {
            if (q.isEmpty()
                    || e.name.toLowerCase(TR).contains(q)
                    || (!qDigits.isEmpty() && e.number.replaceAll("[^0-9]", "").contains(qDigits))) {
                visible.add(e);
            }
        }
        refreshList();
    }

    // ---------------- İzinler ve SIM ----------------

    private boolean granted(String p) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        String[] perms = {Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS};
        List<String> missing = new ArrayList<>();
        for (String p : perms) if (!granted(p)) missing.add(p);
        if (missing.isEmpty()) loadSims();
        else requestPermissions(missing.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (granted(Manifest.permission.READ_PHONE_STATE)) {
            loadSims();
        } else {
            simText.setText("SIM'leri görmek için \"Telefon\" iznini vermelisiniz. Ayarlar > Uygulamalar > VCF → SIM > İzinler.");
            simText.setTextColor(RED);
        }
    }

    private void loadSims() {
        sims.clear();
        try {
            sims.addAll(SimStore.listSims(this));
        } catch (SecurityException e) {
            simText.setText("SIM listesi okunamadı: izin yok.");
            return;
        }
        List<String> titles = new ArrayList<>();
        for (SimStore.Sim s : sims) titles.add(s.title);
        if (titles.isEmpty()) titles.add("SIM bulunamadı");
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, titles);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        simSpinner.setAdapter(a);
        if (sims.isEmpty()) {
            simText.setText("Takılı SIM kart bulunamadı. SIM'i takıp uygulamayı yeniden açın.");
            simText.setTextColor(RED);
            return;
        }
        simSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectSim(sims.get(pos));
            }

            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void selectSim(SimStore.Sim sim) {
        final int gen = ++simGeneration;
        store = null;
        simInfo = null;
        simText.setText("SIM okunuyor…");
        simText.setTextColor(GRAY);
        updateCounter();
        final SimStore s = new SimStore(this, sim);
        new Thread(() -> {
            List<SimStore.Record> recs;
            SimStore.Info info;
            String error = null;
            try {
                recs = s.readAll();
                info = s.info(recs.size());
            } catch (Exception e) {
                recs = new ArrayList<>();
                info = new SimStore.Info();
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final List<SimStore.Record> fr = recs;
            final SimStore.Info fi = info;
            final String fe = error;
            ui.post(() -> {
                if (gen != simGeneration) return;
                store = s;
                simInfo = fi;
                simKeys.clear();
                for (SimStore.Record r : fr) {
                    String k = numberKey(r.number);
                    if (!k.isEmpty()) simKeys.add(k);
                }
                if (fe != null) {
                    simText.setText("SIM rehberi okunamadı (" + fe + "). Yine de yazmayı deneyebilirsiniz.");
                    simText.setTextColor(ORANGE);
                } else if (fi.max > 0) {
                    simText.setText("Kapasite: " + fi.max + " · Dolu: " + fi.used + " · Boş: " + freeSlots()
                            + " · İsim alanı: " + fi.nameMax + " karakter");
                    simText.setTextColor(GREEN);
                } else {
                    simText.setText("SIM'de " + fi.used + " kayıt var. (Kapasite okunamadı; genelde 250.)");
                    simText.setTextColor(GREEN);
                }
                markOnSim();
            });
        }).start();
    }

    private void markOnSim() {
        for (Entry e : all) {
            e.onSim = simKeys.contains(e.key);
            if (e.onSim) e.checked = false;
        }
        refreshList();
    }

    // ---------------- VCF ----------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("*/*");
            startActivityForResult(Intent.createChooser(g, "VCF dosyası seç"), REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadVcf(data.getData());
        }
    }

    private String fileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "dosya" : p;
    }

    private void loadVcf(Uri uri) {
        fileText.setText("Dosya okunuyor…");
        fileText.setTextColor(GRAY);
        final String name = fileName(uri);
        new Thread(() -> {
            try {
                byte[] data;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("dosya açılamadı");
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
                    data = b.toByteArray();
                }
                List<VcfParser.Card> cards = VcfParser.parse(data);
                List<Entry> entries = new ArrayList<>();
                int contactsWithPhone = 0;
                for (VcfParser.Card c : cards) {
                    List<Entry> mine = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    for (VcfParser.Phone p : c.phones) {
                        String num = sanitizeNumber(p.number);
                        String key = numberKey(num);
                        if (num.isEmpty() || !seen.add(key)) continue;
                        Entry e = new Entry();
                        e.name = c.displayName();
                        e.number = num;
                        e.label = p.label;
                        e.key = key;
                        mine.add(e);
                    }
                    for (int i = 0; i < mine.size(); i++) {
                        mine.get(i).phoneIndex = i;
                        mine.get(i).phoneCount = mine.size();
                    }
                    if (!mine.isEmpty()) contactsWithPhone++;
                    entries.addAll(mine);
                }
                final Collator col = Collator.getInstance(TR);
                Collections.sort(entries, (a, b) -> {
                    if (a.name.isEmpty() != b.name.isEmpty()) return a.name.isEmpty() ? 1 : -1;
                    int r = col.compare(a.name, b.name);
                    return r != 0 ? r : Integer.compare(a.phoneIndex, b.phoneIndex);
                });
                final int cardCount = cards.size();
                final int people = contactsWithPhone;
                ui.post(() -> {
                    all.clear();
                    all.addAll(entries);
                    fileText.setText(name + " — " + cardCount + " kişi, " + people + " tanesinde numara var, toplam "
                            + entries.size() + " numara.");
                    fileText.setTextColor(entries.isEmpty() ? RED : GREEN);
                    markOnSim();
                    applyFilter();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    fileText.setText("Dosya okunamadı: " + e.getMessage());
                    fileText.setTextColor(RED);
                });
            }
        }).start();
    }

    /** SIM yalnızca rakam, + (başta), * ve # kabul eder. */
    static String sanitizeNumber(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9' || c == '*' || c == '#') sb.append(c);
            else if (c == '+' && sb.length() == 0) sb.append(c);
            else if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
        }
        return sb.toString();
    }

    /** Aynı numarayı farklı yazımlarda (0532…, +90532…) eşleştirmek için son 10 hane. */
    static String numberKey(String num) {
        String d = num.replaceAll("[^0-9]", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private String simName(Entry e, boolean simplify) {
        String base = e.name.isEmpty() ? e.number : e.name;
        base = base.replaceAll("[\\uD800-\\uDFFF\\uFE0F\\u200D]", "").trim();
        if (base.isEmpty()) base = e.number;
        String suffix = "";
        if (e.phoneCount > 1) suffix = " " + (e.label.isEmpty() ? String.valueOf(e.phoneIndex + 1) : e.label);
        if (simplify) {
            base = SimStore.simplifyTurkish(base);
            suffix = SimStore.simplifyTurkish(suffix);
        }
        int max = simInfo != null && simInfo.nameMax > 0 ? simInfo.nameMax : 14;
        return SimStore.fit(base, suffix, max);
    }

    // ---------------- Yazma ----------------

    private void startWrite() {
        if (store == null) {
            Toast.makeText(this, "Önce bir SIM seçin.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!granted(Manifest.permission.WRITE_CONTACTS)) {
            Toast.makeText(this, "Kişiler izni gerekli.", Toast.LENGTH_LONG).show();
            askPermissions();
            return;
        }
        final List<Entry> sel = new ArrayList<>();
        for (Entry e : all) if (e.checked) sel.add(e);
        if (sel.isEmpty()) return;
        int free = freeSlots();
        if (free == 0) {
            new AlertDialog.Builder(this).setTitle("SIM dolu")
                    .setMessage("Seçtiğiniz SIM'de boş yer yok. Tuşlu telefondan ya da başka bir uygulamadan bazı kayıtları silin.")
                    .setPositiveButton("Tamam", null).show();
            return;
        }
        if (free > 0 && sel.size() > free) {
            new AlertDialog.Builder(this).setTitle("Yer yetmiyor")
                    .setMessage(sel.size() + " kişi seçtiniz ama SIM'de " + free + " boş yer var.\n\n"
                            + "Listedeki ilk " + free + " kişiyi yazayım mı? Ya da seçiminizi azaltın.")
                    .setPositiveButton("İlk " + free + " kişiyi yaz", (d, w) -> doWrite(new ArrayList<>(sel.subList(0, free))))
                    .setNegativeButton("Vazgeç", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Onay")
                .setMessage(sel.size() + " kişi \"" + store.sim.title + "\" kartına yazılacak. Devam edilsin mi?")
                .setPositiveButton("Yaz", (d, w) -> doWrite(sel))
                .setNegativeButton("Vazgeç", null).show();
    }

    private volatile boolean cancelWrite;

    private void doWrite(List<Entry> list) {
        final SimStore s = store;
        final boolean simplify = trBox.isChecked();
        final List<String> names = new ArrayList<>();
        for (Entry e : list) names.add(simName(e, simplify));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(8));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(list.size());
        TextView status = label("Başlıyor…", 14, false);
        box.addView(bar);
        box.addView(status);
        cancelWrite = false;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("SIM'e yazılıyor")
                .setView(box).setCancelable(false)
                .setNegativeButton("Durdur", (d, w) -> cancelWrite = true).show();

        new Thread(() -> {
            int ok = 0;
            int streak = 0;
            String lastError = null;
            List<String> fails = new ArrayList<>();
            for (int i = 0; i < list.size() && !cancelWrite; i++) {
                Entry e = list.get(i);
                String n = names.get(i);
                final int done = i;
                ui.post(() -> {
                    bar.setProgress(done);
                    status.setText((done + 1) + " / " + list.size() + "\n" + n);
                });
                try {
                    s.insert(n, e.number);
                    ok++;
                    streak = 0;
                } catch (Exception ex) {
                    lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    fails.add(n + " (" + e.number + "): " + lastError);
                    if (++streak >= 10) break; // art arda hata: SIM dolu ya da yazma engelli
                }
            }
            final int fOk = ok;
            final boolean stoppedByErrors = streak >= 10;
            final String fLast = lastError;
            ui.post(() -> {
                dialog.dismiss();
                StringBuilder m = new StringBuilder();
                m.append("Yazılan: ").append(fOk).append("\nHatalı: ").append(fails.size());
                if (cancelWrite) m.append("\n\nİşlem durduruldu.");
                if (stoppedByErrors) {
                    m.append("\n\nArt arda 10 hata olduğu için durdum. Son hata: ").append(fLast)
                            .append("\nSIM dolmuş olabilir ya da bu telefon uygulamaların SIM'e yazmasına izin vermiyor olabilir.");
                }
                if (!fails.isEmpty()) {
                    m.append("\n\nHatalı kayıtlar:");
                    for (int i = 0; i < Math.min(fails.size(), 25); i++) m.append("\n• ").append(fails.get(i));
                    if (fails.size() > 25) m.append("\n… ve ").append(fails.size() - 25).append(" tane daha");
                }
                if (fOk > 0) m.append("\n\nArtık SIM'i tuşlu telefona takabilirsiniz.");
                new AlertDialog.Builder(this).setTitle(fOk > 0 ? "Bitti" : "Yazılamadı")
                        .setMessage(m.toString()).setPositiveButton("Tamam", null).show();
                selectSim(s.sim);
            });
        }).start();
    }
}
