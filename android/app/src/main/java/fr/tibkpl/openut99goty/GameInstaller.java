package fr.tibkpl.openut99goty;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prepare tout ce qu'il faut au lancement, dans le stockage prive de l'appli :
 *
 *   files/rootfs/   runtime Linux (Debian arm64 minimal, extrait de l'APK)
 *   files/patch/    patch OldUnreal 469 pour Linux (moteur arm64 System + fichiers mis
 *                   a jour), telecharge depuis github.com/OldUnreal/UnrealTournamentPatches ; vu en /patch
 *   files/game/     dossier du jeu vu par le moteur (/game) : liens vers les donnees de
 *                   l'utilisateur (memoire interne ou carte SD), fichiers du patch par-dessus
 *   files/home/     $HOME du moteur (/home/ut) : ~/.ut99 (configuration, sauvegardes)
 */
final class GameInstaller {

    /** Ce dont le runtime a besoin pour demarrer le moteur. */
    static final class LaunchConfig {
        final File rootfsDir;
        final File patchDir;
        final File gameRoot;  // /game
        final File homeDir;   // /home/ut
        final File dataDir;

        LaunchConfig(File rootfsDir, File patchDir, File gameRoot, File homeDir, File dataDir) {
            this.rootfsDir = rootfsDir;
            this.patchDir = patchDir;
            this.gameRoot = gameRoot;
            this.homeDir = homeDir;
            this.dataDir = dataDir;
        }
    }

    interface Progress {
        void onStep(String message);
    }

    /** Point de montage du patch dans le runtime (cible des liens de files/game). */
    static final String PATCH_MOUNT = "/patch";
    /** Dossier du moteur arm64 dans le patch OldUnreal. */
    private static final String ENGINE_DIR = "System";

    private static final String ASSET_ROOTFS = "runtime/rootfs.tgz";
    private static final String KEY_RUNTIME_VERSION = "runtime_version";
    private static final String PATCH_RELEASES =
            "https://api.github.com/repos/OldUnreal/UnrealTournamentPatches/releases";

    /** Noms des dossiers d'UT99 (les installations Windows peuvent varier la casse). */
    private static final String[] CANONICAL_DIRS = {
            "Animations", "Benchmark", "ForceFeedback", "Help", "KarmaData", "Manual", "Maps",
            "Music", "Sounds", "Speech", "StaticMeshes", "System", "Textures", "Web",
    };

    private GameInstaller() {}

    static File patchDir(Context context) {
        return new File(context.getFilesDir(), "patch");
    }

    static File rootfsDir(Context context) {
        return new File(context.getFilesDir(), "rootfs");
    }

    /** Runtime Linux : reinstalle a chaque nouvelle version de l'APK. */
    static File ensureRuntime(Context context, Progress progress) throws IOException {
        File rootfsDir = rootfsDir(context);
        SharedPreferences prefs = context.getSharedPreferences("openut99goty", Context.MODE_PRIVATE);
        int version = appVersion(context);
        if (prefs.getInt(KEY_RUNTIME_VERSION, -1) != version || !new File(rootfsDir, "opt/ut/run.sh").isFile()) {
            progress.onStep(context.getString(R.string.install_runtime, 0));
            deleteRecursively(rootfsDir);
            AssetManager assets = context.getAssets();
            long total = assetLength(assets);
            try (InputStream in = assets.open(ASSET_ROOTFS)) {
                TarExtractor.extract(in, rootfsDir, read -> progress.onStep(
                        context.getString(R.string.install_runtime, total > 0 ? (int) (read * 100 / total) : 0)));
            }
            prefs.edit().putInt(KEY_RUNTIME_VERSION, version).apply();
        }
        mkdirs(new File(rootfsDir, "tmp"));
        return rootfsDir;
    }

    static LaunchConfig prepare(Context context, File dataDir, Progress progress) throws IOException {
        File rootfsDir = ensureRuntime(context, progress);
        File patchDir = ensurePatch(context, progress);

        progress.onStep(context.getString(R.string.game_preparing));
        File gameRoot = new File(context.getFilesDir(), "game");
        buildGameRoot(dataDir, patchDir, gameRoot);
        File homeDir = new File(context.getFilesDir(), "home");
        mkdirs(new File(homeDir, ".ut99/System"));
        mkdirs(new File(homeDir, "Save"));
        mkdirs(new File(homeDir, "Cache"));
        for (String folder : new String[]{"Save", "Cache"}) {
            File link = new File(gameRoot, folder);
            deleteRecursively(link);
            symlink("/home/ut/" + folder, link);
        }
        LaunchConfig config = new LaunchConfig(rootfsDir, patchDir, gameRoot, homeDir, dataDir);
        File mapCache = new File(context.getFilesDir(), "map-cache");
        mkdirs(mapCache);
        if (restoreCachedMaps(dataDir, mapCache, gameRoot)) {
            android.util.Log.i("OpenUT99GOTY", "Cartes en cache : aucune decompression necessaire");
            return config;
        }
        List<String> binds = new ArrayList<>();
        binds.add(gameRoot.getAbsolutePath() + ":/game");
        binds.add(patchDir.getAbsolutePath() + ":/patch");
        binds.add(mapCache.getAbsolutePath() + ":/map-cache");
        List<String> environment = new ArrayList<>();
        environment.add("LD_LIBRARY_PATH=/game/System");
        ProcessBuilder unpack = GameRuntime.proot(context, rootfsDir, binds, environment, "/game/System",
                "/opt/ut/decompress-maps.sh");
        unpack.redirectErrorStream(true);
        java.lang.Process process = unpack.start();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) progress.onStep(line);
        }
        try {
            if (process.waitFor() != 0) throw new IOException("Decompression des cartes echouee");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        return config;
    }

    /** Restore existing maps without spawning a Linux decompression process. */
    private static boolean restoreCachedMaps(File dataDir, File cache, File gameRoot) throws IOException {
        File maps = GameData.resolve(dataDir, "Maps");
        File[] files = maps == null ? null : maps.listFiles();
        if (files == null) throw new IOException("Lecture impossible : " + maps);
        boolean complete = true;
        File targetMaps = new File(gameRoot, "Maps");
        for (File source : files) {
            if (!source.getName().toLowerCase(Locale.ROOT).endsWith(".unr.uz")) continue;
            String name = source.getName().substring(0, source.getName().length() - 3);
            File target = new File(targetMaps, name);
            if (Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            File map = new File(cache, name);
            File stamp = new File(cache, name + ".source");
            String expected = source.length() + ":" + source.lastModified() / 1000;
            String stored = stamp.isFile() ? new String(Files.readAllBytes(stamp.toPath()), StandardCharsets.UTF_8).trim() : "";
            // Older versions stamped the recreated symlink instead of its source file.
            boolean legacy = stored.startsWith(source.getAbsolutePath().getBytes(StandardCharsets.UTF_8).length + ":")
                    && map.lastModified() >= source.lastModified();
            boolean packageValid = false;
            if (map.isFile()) try (java.io.InputStream input = new java.io.FileInputStream(map)) {
                packageValid = input.read() == 0xc1 && input.read() == 0x83 && input.read() == 0x2a && input.read() == 0x9e;
            }
            if (!packageValid || (!stored.equals(expected) && !legacy)) { complete = false; continue; }
            if (legacy && !stored.equals(expected)) Files.write(stamp.toPath(), (expected + "\n").getBytes(StandardCharsets.UTF_8));
            symlink("/map-cache/" + name, target);
        }
        File alias = new File(targetMaps, "DOM-Cybrosis][.unr");
        if (!Files.exists(alias.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.exists(new File(targetMaps, "DM-Cybrosis][.unr").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            symlink("DM-Cybrosis][.unr", alias);
        return complete;
    }

    // ---- Patch OldUnreal ----

    /** Patch OldUnreal pour Linux (derniere version publiee), telecharge au premier lancement. */
    static File ensurePatch(Context context, Progress progress) throws IOException {
        File patchDir = new File(context.getFilesDir(), "patch");
        if (new File(patchDir, "System/ut-bin-arm64").isFile()) return patchDir;

        progress.onStep(context.getString(R.string.install_patch, 0));
        String url;
        String sha256 = null;
        long size;
        String tag;
        try {
            JSONArray releases = new JSONArray(new String(Http.fetch(PATCH_RELEASES), StandardCharsets.UTF_8));
            JSONObject release = releases.getJSONObject(0);
            tag = release.getString("tag_name");
            JSONObject asset = null;
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                String name = assets.getJSONObject(i).getString("name");
                if (name.contains("-Linux-arm64") && !name.contains("-SDK-") && name.endsWith(".tar.bz2")) asset = assets.getJSONObject(i);
            }
            if (asset == null) throw new IOException("Patch Linux absent de la release " + tag);
            url = asset.getString("browser_download_url");
            size = asset.optLong("size", -1);
            String digest = asset.optString("digest", "");
            if (digest.startsWith("sha256:")) sha256 = digest.substring(7);
        } catch (JSONException e) {
            throw new IOException("Informations du patch OldUnreal illisibles", e);
        }

        File archive = new File(context.getCacheDir(), "ut99-patch.tar.bz2");
        Http.download(url, archive, size, sha256, false,
                (done, total) -> progress.onStep(context.getString(R.string.install_patch,
                        total > 0 ? (int) (done * 100 / total) : 0)));

        progress.onStep(context.getString(R.string.install_patch_extract));
        File tmp = new File(context.getFilesDir(), "patch.tmp");
        deleteRecursively(tmp);
        extractTarBz2(archive, tmp);
        archive.delete();
        Files.write(new File(tmp, "OPENUT99GOTY_PATCH_VERSION").toPath(), tag.getBytes(StandardCharsets.UTF_8));
        deleteRecursively(patchDir);
        if (!tmp.renameTo(patchDir)) throw new IOException("Installation du patch impossible");
        return patchDir;
    }

    private static void extractTarBz2(File archive, File dest) throws IOException {
        String destPath = dest.getCanonicalPath() + File.separator;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16)))) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[1 << 16];
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                while (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty()) continue;
                File target = new File(dest, name);
                if (!target.getCanonicalPath().startsWith(destPath)) throw new IOException("Chemin invalide : " + name);
                if (entry.isDirectory()) {
                    mkdirs(target);
                } else if (entry.isSymbolicLink()) {
                    mkdirs(target.getParentFile());
                    target.delete();
                    symlink(entry.getLinkName(), target);
                } else {
                    mkdirs(target.getParentFile());
                    try (OutputStream out = new FileOutputStream(target)) {
                        int n;
                        while ((n = tar.read(buffer)) > 0) out.write(buffer, 0, n);
                    }
                    try {
                        Os.chmod(target.getPath(), (entry.getMode() & 0777) | 0600);
                    } catch (ErrnoException e) {
                        throw new IOException("chmod " + target, e);
                    }
                }
            }
        }
    }

    // ---- Dossier du jeu vu par le moteur ----

    /**
     * Reconstruit files/game : un lien par dossier de donnees (Maps, Music...), et pour les
     * dossiers que le patch met a jour (System, Textures...) un dossier ou chaque fichier est
     * un lien vers les donnees, remplace par le fichier du patch s'il existe. Les liens pointent
     * vers les chemins vus dans le runtime (/storage/... et /patch/...).
     */
    static void buildGameRoot(File dataDir, File patchDir, File gameRoot) throws IOException {
        // Tout est recree sauf le dossier du moteur, recopie seulement si le patch change
        File engineDir = new File(gameRoot, ENGINE_DIR);
        File[] old = gameRoot.listFiles();
        if (old != null) for (File f : old) if (!f.getName().equals(ENGINE_DIR)) deleteRecursively(f);
        mkdirs(gameRoot);
        mkdirs(engineDir);
        File baseSystem = GameData.resolve(dataDir, "System");
        if (baseSystem != null) linkTree(baseSystem, engineDir);
        installEngineDir(new File(patchDir, ENGINE_DIR), engineDir);

        Map<String, File> patchEntries = new HashMap<>();
        File[] patchFiles = patchDir.listFiles();
        if (patchFiles != null) {
            for (File f : patchFiles) {
                if (f.isDirectory() && !f.getName().equals(ENGINE_DIR)) patchEntries.put(f.getName().toLowerCase(Locale.ROOT), f);
            }
        }

        File[] dataEntries = dataDir.listFiles();
        if (dataEntries == null) throw new IOException("Lecture impossible : " + dataDir);
        for (File data : dataEntries) {
            if (!data.isDirectory()) continue;
            String name = canonical(data.getName());
            if (name.equalsIgnoreCase("Installer") || name.equals(ENGINE_DIR)) continue;
            File patch = patchEntries.remove(name.toLowerCase(Locale.ROOT));
            File target = new File(gameRoot, name);
            if (patch == null) {
                if (name.equals("Maps")) linkTree(data, target);
                else symlink(data.getAbsolutePath(), target);
            } else {
                linkTree(data, target);
                overlay(patch, PATCH_MOUNT + "/" + patch.getName(), target);
            }
        }
        // Autres dossiers propres au patch (SystemLocalized...)
        for (File patch : patchEntries.values()) {
            if (patch.getName().equals(ENGINE_DIR)) continue;
            symlink(PATCH_MOUNT + "/" + patch.getName(), new File(gameRoot, patch.getName()));
        }
    }

    /**
     * Le moteur tourne depuis /game/System et trouve ses donnees en ../Maps, ../Help... :
     * ce dossier doit etre un vrai dossier de /game (si c'etait un lien vers /patch, ".."
     * designerait /patch). Copie du patch, refaite seulement quand le patch change.
     */
    private static void installEngineDir(File src, File dest) throws IOException {
        File marker = new File(dest, ".openut99goty-version");
        String version = new String(Files.readAllBytes(
                new File(src.getParentFile(), "OPENUT99GOTY_PATCH_VERSION").toPath()), StandardCharsets.UTF_8);
        if (!marker.isFile() || !version.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8))) {
            copyTree(src, dest);
            Files.write(marker.toPath(), version.getBytes(StandardCharsets.UTF_8));
        }
        // Le moteur cherche Packages.md5 dans son propre dossier ; le patch le fournit dans
        // System/ (et SystemPPC64LE/) mais pas dans System/
        File md5 = new File(dest, "Packages.md5");
        File patchMd5 = new File(src.getParentFile(), "System/Packages.md5");
        if (!md5.exists() && patchMd5.isFile()) Files.copy(patchMd5.toPath(), md5.toPath());
    }

    /** Copie recursive en conservant liens symboliques et droits (executables). */
    private static void copyTree(File src, File dest) throws IOException {
        mkdirs(dest);
        File[] children = src.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(dest, child.getName());
            if (Files.isSymbolicLink(target.toPath()) || target.isFile()) target.delete();
            if (Files.isSymbolicLink(child.toPath())) {
                symlink(Files.readSymbolicLink(child.toPath()).toString(), target);
            } else if (child.isDirectory()) {
                copyTree(child, target);
            } else {
                Files.copy(child.toPath(), target.toPath());
                try {
                    Os.chmod(target.getPath(), Os.stat(child.getPath()).st_mode & 0777);
                } catch (ErrnoException e) {
                    throw new IOException("chmod " + target, e);
                }
            }
        }
    }

    private static String canonical(String name) {
        for (String c : CANONICAL_DIRS) if (c.equalsIgnoreCase(name)) return c;
        return name;
    }

    /** Recree l'arborescence de src dans dest avec des liens vers chaque fichier. */
    private static void linkTree(File src, File dest) throws IOException {
        mkdirs(dest);
        File[] children = src.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(dest, child.getName());
            if (child.isDirectory()) linkTree(child, target);
            else {
                // Keep engine-generated profile files and patch files between launches.
                if (Files.isSymbolicLink(target.toPath())) target.delete();
                else if (target.exists()) continue;
                symlink(child.getAbsolutePath(), target);
            }
        }
    }

    /** Place les fichiers du patch (liens vers /patch/...) en remplacant ceux des donnees. */
    private static void overlay(File patch, String mountPath, File dest) throws IOException {
        mkdirs(dest);
        File[] children = patch.listFiles();
        if (children == null) return;
        for (File child : children) {
            String childMount = mountPath + "/" + child.getName();
            if (child.isDirectory()) {
                overlay(child, childMount, existingIgnoreCase(dest, child.getName()));
                continue;
            }
            // Remplace le fichier des donnees, y compris s'il differe par la casse
            File existing = existingIgnoreCase(dest, child.getName());
            if (Files.isSymbolicLink(existing.toPath()) || existing.exists()) existing.delete();
            symlink(childMount, new File(dest, child.getName()));
        }
    }

    private static File existingIgnoreCase(File dir, String name) {
        String[] names = dir.list();
        if (names != null) {
            for (String n : names) if (n.equalsIgnoreCase(name)) return new File(dir, n);
        }
        return new File(dir, name);
    }

    // ---- Configuration du moteur ----

    /**
     * ~/.ut99/System/UT99.ini : cree depuis Default.ini du moteur arm64 au premier
     * lancement, puis rendu OpenGL et resolution plein ecran au format de l'ecran.
     */
    static void prepareConfig(LaunchConfig config, int width, int height, String language) throws IOException {
        File ini = new File(config.homeDir, "UnrealTournament.ini");
        List<String> lines;
        if (ini.isFile()) {
            lines = new ArrayList<>(Files.readAllLines(ini.toPath(), StandardCharsets.ISO_8859_1));
        } else {
            File defaults = new File(config.patchDir, "System/Default.ini");
            lines = new ArrayList<>(Files.readAllLines(defaults.toPath(), StandardCharsets.ISO_8859_1));
        }
        for (String key : new String[]{"GameRenderDevice", "WindowedRenderDevice", "RenderDevice"})
            Ini.set(lines, "Engine.Engine", key, "OpenGLDrv.OpenGLRenderDevice");
        Ini.set(lines, "Engine.Engine", "Language", language);
        Ini.set(lines, "Engine.Engine", "AudioDevice", "ALAudio.ALAudioSubsystem");
        // Toutes les tailles a la resolution choisie : l'image remplit l'ecran X virtuel meme si
        // le moteur demarre en mode fenetre (pas de gestionnaire de fenetres, fenetre en 0,0)
        for (String section : new String[]{"SDLDrv.SDLClient", "WinDrv.WindowsClient"}) {
            for (String mode : new String[]{"Fullscreen", "Windowed", "Menu"}) {
                Ini.set(lines, section, mode + "ViewportX", Integer.toString(width));
                Ini.set(lines, section, mode + "ViewportY", Integer.toString(height));
            }
            Ini.set(lines, section, "StartupFullscreen", "True");
        }
        Ini.set(lines, "SDLDrv.SDLClient", "UseJoystick", "False");
        Ini.set(lines, "FirstRun", "FirstRun", "0");
        File userIni = new File(config.homeDir, "User.ini");
        List<String> userLines = userIni.isFile()
                ? new ArrayList<>(Files.readAllLines(userIni.toPath(), StandardCharsets.ISO_8859_1))
                : new ArrayList<>(Files.readAllLines(new File(config.patchDir, "System/DefUser.ini").toPath(), StandardCharsets.ISO_8859_1));
        for (String[] binding : new String[][]{{"F5", "PrevWeapon"}, {"F6", "NextWeapon"}, {"W", "MoveForward"}, {"S", "MoveBackward"}, {"A", "StrafeLeft"}, {"D", "StrafeRight"}, {"Space", "Jump"}, {"C", "Duck"}, {"G", "SwitchToBestWeapon"}, {"MouseWheelUp", "PrevWeapon"}, {"MouseWheelDown", "NextWeapon"}})
            Ini.set(userLines, "Engine.Input", binding[0], binding[1]);
        mkdirs(config.homeDir);
        mkdirs(new File(config.homeDir, "Save"));
        mkdirs(new File(config.homeDir, "Cache"));
        Ini.set(lines, "Core.System", "SavePath", "../Save");
        Ini.set(lines, "Core.System", "CachePath", "../Cache");
        Files.write(userIni.toPath(), String.join("\n", userLines).concat("\n").getBytes(StandardCharsets.ISO_8859_1));
        mkdirs(ini.getParentFile());
        Files.write(ini.toPath(), String.join("\n", lines).concat("\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    // ---- Utilitaires ----

    private static long assetLength(AssetManager assets) {
        try (android.content.res.AssetFileDescriptor fd = assets.openFd(ASSET_ROOTFS)) {
            return fd.getLength();
        } catch (IOException e) {
            return -1;
        }
    }

    static void deleteRecursively(File file) {
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static void symlink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("Lien impossible " + link + " -> " + target + " : " + e.getMessage(), e);
        }
    }

    static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Creation impossible : " + dir);
    }

    @SuppressWarnings("deprecation")
    private static int appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }
}
