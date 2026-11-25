package ch.lenny.triphoto;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Size;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;
    private static final int REQ_DELETE = 2;
    private static final int REQ_DELETE_ONE = 3;

    private static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

    private static final class Photo {
        final long id;
        final Uri uri;
        Photo(long id) {
            this.id = id;
            this.uri = ContentUris.withAppendedId(IMAGES, id);
        }
        String key() { return String.valueOf(id); }
    }

    private final List<Photo> photos = new ArrayList<>();
    private final List<Integer> history = new ArrayList<>();
    private final Set<String> kept = new HashSet<>();
    private final Set<String> pending = new HashSet<>();
    private final Map<Long, Bitmap> cache = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private int idx = 0;
    private boolean busy = false;
    private boolean loaded = false;
    private boolean askedOnce = false;

    private View card, deck, doneView, permView;
    private ImageView topImg, nextImg;
    private TextView stampKeep, stampDrop, counter, deleteBtn, undoBtn, yesBtn, noBtn;
    private TextView doneText, doneDelete, doneReset, permText, permBtn;

    // ---------- cycle de vie ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("tri", MODE_PRIVATE);
        kept.addAll(prefs.getStringSet("kept", new HashSet<>()));
        pending.addAll(prefs.getStringSet("pending", new HashSet<>()));

        card = findViewById(R.id.card);
        deck = findViewById(R.id.deck);
        doneView = findViewById(R.id.doneView);
        permView = findViewById(R.id.permView);
        topImg = findViewById(R.id.topImg);
        nextImg = findViewById(R.id.nextImg);
        stampKeep = findViewById(R.id.stampKeep);
        stampDrop = findViewById(R.id.stampDrop);
        counter = findViewById(R.id.counter);
        deleteBtn = findViewById(R.id.deleteBtn);
        undoBtn = findViewById(R.id.undoBtn);
        yesBtn = findViewById(R.id.yesBtn);
        noBtn = findViewById(R.id.noBtn);
        doneText = findViewById(R.id.doneText);
        doneDelete = findViewById(R.id.doneDelete);
        doneReset = findViewById(R.id.doneReset);
        permText = findViewById(R.id.permText);
        permBtn = findViewById(R.id.permBtn);

        card.setClipToOutline(true);
        nextImg.setClipToOutline(true);

        yesBtn.setOnClickListener(v -> { if (canSwipe()) fling(true); });
        noBtn.setOnClickListener(v -> { if (canSwipe()) fling(false); });
        undoBtn.setOnClickListener(v -> undo());
        deleteBtn.setOnClickListener(v -> deletePending());
        doneDelete.setOnClickListener(v -> deletePending());
        doneReset.setOnClickListener(v -> {
            kept.clear();
            save();
            load();
        });
        permBtn.setOnClickListener(v -> askPermission());

        card.setOnTouchListener(new SwipeTouch());
        updateTop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermission()) {
            permView.setVisibility(View.GONE);
            if (!loaded) load();
        } else {
            permView.setVisibility(View.VISIBLE);
            if (!askedOnce) askPermission();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---------- permissions ----------

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        } else if (Build.VERSION.SDK_INT <= 28) {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasPermission() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void askPermission() {
        String[] perms = neededPermissions();
        if (askedOnce && !shouldShowRequestPermissionRationale(perms[0])) {
            // Refus définitif : seule la page des réglages peut rendre l'accès.
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
            return;
        }
        askedOnce = true;
        requestPermissions(perms, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (hasPermission()) {
            permView.setVisibility(View.GONE);
            load();
        } else {
            permView.setVisibility(View.VISIBLE);
            permText.setText(R.string.perm_denied);
            if (!shouldShowRequestPermissionRationale(neededPermissions()[0])) {
                permBtn.setText(R.string.perm_settings);
            }
        }
    }

    // ---------- chargement de la galerie ----------

    private void load() {
        final Set<String> skip = new HashSet<>(kept);
        skip.addAll(pending);
        io.execute(() -> {
            final List<Photo> list = new ArrayList<>();
            final Set<String> existing = new HashSet<>();
            String[] proj = {MediaStore.Images.Media._ID};
            String order = MediaStore.Images.Media.DATE_ADDED + " DESC";
            try (Cursor c = getContentResolver().query(IMAGES, proj, null, null, order)) {
                if (c != null) {
                    int col = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (c.moveToNext()) {
                        long id = c.getLong(col);
                        String k = String.valueOf(id);
                        existing.add(k);
                        if (!skip.contains(k)) list.add(new Photo(id));
                    }
                }
            } catch (Exception e) {
                main.post(() -> toast("Impossible de lire la galerie : " + e.getMessage()));
            }
            main.post(() -> {
                photos.clear();
                photos.addAll(list);
                history.clear();
                cache.clear();
                idx = 0;
                loaded = true;
                // On oublie les photos qui n'existent plus.
                kept.retainAll(existing);
                pending.retainAll(existing);
                save();
                show();
            });
        });
    }

    // ---------- affichage ----------

    private boolean canSwipe() {
        return !busy && idx < photos.size();
    }

    private void show() {
        updateTop();
        if (idx >= photos.size()) {
            card.setVisibility(View.INVISIBLE);
            nextImg.setVisibility(View.INVISIBLE);
            doneView.setVisibility(View.VISIBLE);
            int n = pending.size();
            String t;
            if (photos.isEmpty() && kept.isEmpty() && n == 0) {
                t = "Aucune photo trouvée dans ta galerie.";
            } else if (n > 0) {
                t = "Tout est trié !\n" + n + (n == 1 ? " photo attend" : " photos attendent")
                        + " d'être supprimée" + (n == 1 ? "." : "s.");
            } else {
                t = "Tout est trié !\nIl n'y a rien à supprimer.";
            }
            doneText.setText(t);
            doneDelete.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            doneReset.setVisibility(kept.isEmpty() ? View.GONE : View.VISIBLE);
            return;
        }
        doneView.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        resetCard();
        bind(topImg, photos.get(idx));
        if (idx + 1 < photos.size()) {
            nextImg.setVisibility(View.VISIBLE);
            bind(nextImg, photos.get(idx + 1));
        } else {
            nextImg.setVisibility(View.INVISIBLE);
            nextImg.setImageDrawable(null);
        }
        if (idx + 2 < photos.size()) prefetch(photos.get(idx + 2));
        trimCache();
    }

    private void updateTop() {
        int remaining = Math.max(0, photos.size() - idx);
        counter.setText(loaded ? remaining + (remaining == 1 ? " photo à trier" : " photos à trier") : "");
        int n = pending.size();
        deleteBtn.setText("Supprimer (" + n + ")");
        deleteBtn.setEnabled(n > 0);
        deleteBtn.setAlpha(n > 0 ? 1f : 0.35f);
        undoBtn.setEnabled(!history.isEmpty());
        undoBtn.setAlpha(history.isEmpty() ? 0.3f : 1f);
        boolean can = idx < photos.size();
        yesBtn.setAlpha(can ? 1f : 0.3f);
        noBtn.setAlpha(can ? 1f : 0.3f);
    }

    private void resetCard() {
        card.animate().cancel();
        card.setTranslationX(0);
        card.setTranslationY(0);
        card.setRotation(0);
        card.setAlpha(1f);
        stampKeep.setAlpha(0f);
        stampDrop.setAlpha(0f);
    }

    // ---------- images ----------

    private void bind(ImageView v, Photo p) {
        v.setTag(p.id);
        Bitmap b = cache.get(p.id);
        if (b != null) {
            v.setImageBitmap(b);
            return;
        }
        v.setImageDrawable(null);
        loadAsync(p, bmp -> {
            Object tag = v.getTag();
            if (bmp != null && tag instanceof Long && (Long) tag == p.id) v.setImageBitmap(bmp);
        });
    }

    private void prefetch(Photo p) {
        if (!cache.containsKey(p.id)) loadAsync(p, bmp -> { });
    }

    private void loadAsync(Photo p, Consumer<Bitmap> done) {
        io.execute(() -> {
            Bitmap b = decode(p.uri);
            main.post(() -> {
                if (b != null) cache.put(p.id, b);
                done.accept(b);
            });
        });
    }

    private void trimCache() {
        Set<Long> keep = new HashSet<>();
        for (int i = Math.max(0, idx - 1); i < Math.min(photos.size(), idx + 3); i++) {
            keep.add(photos.get(i).id);
        }
        Iterator<Long> it = cache.keySet().iterator();
        while (it.hasNext()) {
            if (!keep.contains(it.next())) it.remove();
        }
    }

    private Bitmap decode(Uri uri) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return getContentResolver().loadThumbnail(uri, new Size(w, h), null);
            } catch (Exception ignored) {
                // on tente le décodage classique ci-dessous
            }
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int target = Math.max(w, h);
            int big = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (big / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) return null;
            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ExifInterface exif = new ExifInterface(in);
                int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Exception ignored) {
                // pas d'EXIF : on garde l'image telle quelle
            }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- swipe ----------

    private final class SwipeTouch implements View.OnTouchListener {
        private float x0, y0;
        private VelocityTracker vt;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (!canSwipe()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    card.animate().cancel();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (vt != null) vt.addMovement(e);
                    float dx = e.getRawX() - x0;
                    float dy = e.getRawY() - y0;
                    card.setTranslationX(dx);
                    card.setTranslationY(dy * 0.3f);
                    card.setRotation(dx / 20f);
                    float lim = Math.max(1f, card.getWidth() * 0.25f);
                    stampKeep.setAlpha(clamp(dx / lim));
                    stampDrop.setAlpha(clamp(-dx / lim));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dx = e.getRawX() - x0;
                    float vx = 0;
                    if (vt != null) {
                        vt.addMovement(e);
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                        vt.recycle();
                        vt = null;
                    }
                    float lim = card.getWidth() * 0.25f;
                    float flingSpeed = 1000 * getResources().getDisplayMetrics().density;
                    boolean far = Math.abs(dx) > lim;
                    boolean quick = Math.abs(vx) > flingSpeed && Math.abs(dx) > lim / 3
                            && Math.signum(vx) == Math.signum(dx);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && (far || quick)) {
                        fling(dx > 0);
                    } else {
                        snapBack();
                    }
                    return true;
                }
                default:
                    return false;
            }
        }
    }

    private static float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    private void snapBack() {
        card.animate().translationX(0).translationY(0).rotation(0).setDuration(180).start();
        stampKeep.animate().alpha(0f).setDuration(150).start();
        stampDrop.animate().alpha(0f).setDuration(150).start();
    }

    private void fling(boolean keep) {
        busy = true;
        float dir = keep ? 1f : -1f;
        (keep ? stampKeep : stampDrop).setAlpha(1f);
        card.animate()
                .translationX(dir * card.getWidth() * 1.5f)
                .translationY(card.getTranslationY() + 60)
                .rotation(dir * 25)
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> {
                    busy = false;
                    decide(keep);
                })
                .start();
    }

    private void decide(boolean keep) {
        if (idx >= photos.size()) return;
        String k = photos.get(idx).key();
        if (keep) kept.add(k); else pending.add(k);
        history.add(idx);
        idx++;
        save();
        show();
    }

    private void undo() {
        if (busy || history.isEmpty()) return;
        int i = history.remove(history.size() - 1);
        String k = photos.get(i).key();
        kept.remove(k);
        pending.remove(k);
        idx = i;
        save();
        show();
    }

    // ---------- suppression ----------

    private List<Uri> pendingUris() {
        List<Uri> uris = new ArrayList<>();
        for (String k : pending) uris.add(ContentUris.withAppendedId(IMAGES, Long.parseLong(k)));
        return uris;
    }

    private void deletePending() {
        if (pending.isEmpty() || busy) return;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                // Android affiche sa propre fenêtre de confirmation, une seule fois pour tout le lot.
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), pendingUris());
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                toast("Suppression impossible : " + e.getMessage());
            }
        } else {
            deleteLegacy();
        }
    }

    private void deleteLegacy() {
        final List<String> todo = new ArrayList<>(pending);
        io.execute(() -> {
            final List<String> done = new ArrayList<>();
            IntentSender ask = null;
            for (String k : todo) {
                Uri u = ContentUris.withAppendedId(IMAGES, Long.parseLong(k));
                try {
                    getContentResolver().delete(u, null, null);
                    done.add(k);
                } catch (SecurityException se) {
                    if (Build.VERSION.SDK_INT >= 29 && se instanceof RecoverableSecurityException) {
                        ask = ((RecoverableSecurityException) se).getUserAction()
                                .getActionIntent().getIntentSender();
                        break;
                    }
                }
            }
            final IntentSender confirm = ask;
            main.post(() -> {
                pending.removeAll(done);
                save();
                if (confirm != null) {
                    try {
                        startIntentSenderForResult(confirm, REQ_DELETE_ONE, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException ex) {
                        toast("Suppression impossible.");
                        afterDelete(done.size());
                    }
                } else {
                    afterDelete(done.size());
                }
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) {
                int n = pending.size();
                pending.clear();
                save();
                afterDelete(n);
            } else {
                toast("Suppression annulée.");
            }
        } else if (requestCode == REQ_DELETE_ONE) {
            if (resultCode == RESULT_OK) deleteLegacy();
            else toast("Suppression annulée.");
        }
    }

    private void afterDelete(int n) {
        if (n > 0) toast(n + (n == 1 ? " photo supprimée." : " photos supprimées."));
        history.clear(); // on ne peut pas annuler une photo déjà effacée
        show();
    }

    // ---------- utilitaires ----------

    private void save() {
        prefs.edit()
                .putStringSet("kept", new HashSet<>(kept))
                .putStringSet("pending", new HashSet<>(pending))
                .apply();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
