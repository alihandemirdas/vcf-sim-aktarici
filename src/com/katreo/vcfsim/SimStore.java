package com.katreo.vcfsim;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.SimPhonebookContract.ElementaryFiles;
import android.provider.SimPhonebookContract.SimRecords;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SIM rehberine (ADN) okuma/yazma.
 * Android 12+ : SimPhonebookContract (com.android.simphonebook)
 * Daha eski ya da reddedilirse : content://icc/adn/subId/N
 */
final class SimStore {

    static final class Sim {
        int subId;
        int slot;
        String title;
    }

    static final class Info {
        int max = -1;       // toplam kayıt sayısı (bilinmiyorsa -1)
        int used = 0;
        int nameMax = 14;   // isim alanı, bayt olarak
        int numberMax = 20;
    }

    static final class Record {
        final String name;
        final String number;

        Record(String name, String number) {
            this.name = name;
            this.number = number;
        }
    }

    final Sim sim;
    private final ContentResolver cr;
    private boolean modern = Build.VERSION.SDK_INT >= 31;
    private int nameMax = 14;

    SimStore(Context ctx, Sim sim) {
        this.sim = sim;
        this.cr = ctx.getContentResolver();
    }

    static List<Sim> listSims(Context ctx) {
        List<Sim> out = new ArrayList<>();
        SubscriptionManager sm = (SubscriptionManager) ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (sm == null) return out;
        List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
        if (list == null) return out;
        for (SubscriptionInfo i : list) {
            Sim s = new Sim();
            s.subId = i.getSubscriptionId();
            s.slot = i.getSimSlotIndex();
            CharSequence name = i.getDisplayName();
            if (name == null || name.length() == 0) name = i.getCarrierName();
            s.title = "SIM " + (s.slot + 1) + (name != null && name.length() > 0 ? " — " + name : "");
            out.add(s);
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.slot, b.slot));
        return out;
    }

    Info info(int recordCount) {
        Info info = new Info();
        info.used = recordCount;
        if (modern) {
            try (Cursor c = cr.query(ElementaryFiles.getItemUri(sim.subId, ElementaryFiles.EF_ADN),
                    null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    info.max = c.getInt(c.getColumnIndexOrThrow(ElementaryFiles.MAX_RECORDS));
                    info.used = c.getInt(c.getColumnIndexOrThrow(ElementaryFiles.RECORD_COUNT));
                    int nm = c.getInt(c.getColumnIndexOrThrow(ElementaryFiles.NAME_MAX_LENGTH));
                    int pm = c.getInt(c.getColumnIndexOrThrow(ElementaryFiles.PHONE_NUMBER_MAX_LENGTH));
                    if (nm > 0) info.nameMax = nm;
                    if (pm > 0) info.numberMax = pm;
                    if (info.max <= 0) info.max = -1;
                }
            } catch (Exception e) {
                // bazı üreticilerde yok; eski yola düş
            }
        }
        nameMax = info.nameMax;
        return info;
    }

    List<Record> readAll() {
        if (modern) {
            try {
                return read(SimRecords.getContentUri(sim.subId, ElementaryFiles.EF_ADN),
                        SimRecords.NAME, SimRecords.PHONE_NUMBER);
            } catch (Exception e) {
                modern = false;
            }
        }
        return read(legacyUri(), "name", "number");
    }

    private List<Record> read(Uri uri, String nameCol, String numberCol) {
        List<Record> out = new ArrayList<>();
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c == null) return out;
            int ni = c.getColumnIndex(nameCol);
            int pi = c.getColumnIndex(numberCol);
            while (c.moveToNext()) {
                String name = ni >= 0 ? c.getString(ni) : null;
                String num = pi >= 0 ? c.getString(pi) : null;
                if ((name == null || name.isEmpty()) && (num == null || num.isEmpty())) continue;
                out.add(new Record(name == null ? "" : name, num == null ? "" : num));
            }
        }
        return out;
    }

    void insert(String name, String number) {
        if (modern) {
            try {
                insertModern(name, number);
                return;
            } catch (IllegalArgumentException e) {
                // isim SIM'in kendi ölçüsüne göre uzun olabilir: kısaltıp bir daha dene
                String n = name;
                while (n.length() > 1 && SimRecords.getEncodedNameLength(cr, n) > nameMax) {
                    n = n.substring(0, n.length() - 1).trim();
                }
                if (n.equals(name)) throw e;
                insertModern(n, number);
                return;
            } catch (SecurityException | UnsupportedOperationException e) {
                modern = false;
            }
        }
        ContentValues v = new ContentValues();
        v.put("tag", name);
        v.put("number", number);
        Uri r = cr.insert(legacyUri(), v);
        if (r == null) throw new IllegalStateException("SIM kaydı reddetti (SIM dolu olabilir)");
    }

    private void insertModern(String name, String number) {
        ContentValues v = new ContentValues();
        v.put(SimRecords.NAME, name);
        v.put(SimRecords.PHONE_NUMBER, number);
        Uri r = cr.insert(SimRecords.getContentUri(sim.subId, ElementaryFiles.EF_ADN), v);
        if (r == null) throw new IllegalStateException("SIM kaydı reddetti (SIM dolu olabilir)");
    }

    private Uri legacyUri() {
        return Uri.parse("content://icc/adn/subId/" + sim.subId);
    }

    // ---- SIM isim alanı hesapları ----

    private static final String GSM_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXT = "^{}\\[~]|€\f";

    /** İsmin SIM'de kaç bayt tutacağı: GSM alfabesine sığarsa 1 bayt/harf, yoksa UCS2 (1 + 2/harf). */
    static int adnLength(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (GSM_BASIC.indexOf(c) >= 0) n += 1;
            else if (GSM_EXT.indexOf(c) >= 0) n += 2;
            else return 1 + 2 * s.length();
        }
        return n;
    }

    /** ş, ğ, ı gibi harfler GSM alfabesinde yok; bunlar varsa isim UCS2'ye düşer ve ~6 harfe iner. */
    static String simplifyTurkish(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'ş': c = 's'; break;
                case 'Ş': c = 'S'; break;
                case 'ğ': c = 'g'; break;
                case 'Ğ': c = 'G'; break;
                case 'ı': c = 'i'; break;
                case 'İ': c = 'I'; break;
                case 'ç': c = 'c'; break;
                case 'â': c = 'a'; break;
                case 'Â': c = 'A'; break;
                case 'î': c = 'i'; break;
                case 'Î': c = 'I'; break;
                case 'û': c = 'u'; break;
                case 'Û': c = 'U'; break;
                case 'ê': c = 'e'; break;
                case 'ô': c = 'o'; break;
                default: break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String fit(String base, String suffix, int maxBytes) {
        String b = base;
        while (b.length() > 1 && adnLength(b + suffix) > maxBytes) b = b.substring(0, b.length() - 1).trim();
        String out = b + suffix;
        if (adnLength(out) > maxBytes) {
            out = base;
            while (out.length() > 1 && adnLength(out) > maxBytes) out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }
}
