import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Stiva Feasycom falsă, pentru emulator: face ce face /oem/bin/blueware - un pty
 * real, cu /dev/goc_serial spre el - și răspunde ca în jurnalul de pe unitate
 * (.unit-dump/bt-2026-11-03-night). Launcher-ul nu are niciun cod de test: vede
 * același fișier ca în mașină. Rulează ca root, printr-un app_process; vezi
 * run.ps1 alăturat.
 *
 * În /data/local/tmp/fake-goc.log rămâne tot traficul. Liniile "FORBIDDEN"
 * (comenzi HFP/agendă) și "ECHO" (pty-ul nu e raw) înseamnă test picat.
 *
 * Telefonul se comandă prin FIFO: echo drop > /data/local/tmp/fake-goc.ctl
 *   drop    telefonul pleacă          phone   telefonul se leagă singur
 *   paired  împerecherea a reușit     hfp     telefonul își ridică HFP-ul
 *   poll    MG ca de la bTSuite       die     stiva moare (pty-ul se închide)
 */
public class FakeGoc {
    private static final String LINK = "/dev/goc_serial";
    private static final String LOG = "/data/local/tmp/fake-goc.log";
    private static final String CTL = "/data/local/tmp/fake-goc.ctl";
    private static final int TIOCSPTLCK = 0x40045431;
    private static final int TIOCGPTN = 0x80045430;

    private static final String ADDR = "0A1B2C3D4E5F";
    private static final String PHONE = "iPhone - Costi";

    /** Titluri cu diacritice și cu apostrof, ca să se vadă UTF-8 și 0xFF. */
    private static final String[][] TRACKS = {
        {"Dance Monkey (Extended Club Mix Remastered Version)", "Tones and I", "The Kids Are Coming", "209000"},
        {"Acele", "Carla's Dreams", "Antiexemplu", "206756"},
        {"Timpul", "Feli", "Antiexemplu", "206755"},
        {"Powerful", "Major Lazer, Ellie Goulding, Tarrus Riley", "Peace Is The Mission", "215000"},
        {"Vară fără țară", "Șuie Paparude", "Live", "180000"},
    };

    private static FileDescriptor master;
    private static PrintWriter log;
    private static String name = "Lazar's BMW";
    private static String pin = "0000";
    /** Starea A2DP cu aceleași numere ca stiva: 1 liber, 2 în curs, 3 legat, 4 pauză, 5 cântă. */
    private static volatile int av = 1;
    private static int track = 0;
    private static boolean clientSeen = false;

    public static void main(String[] args) throws Exception {
        log = new PrintWriter(new java.io.FileWriter(LOG, false), true);
        openPty();
        startControl();
        // Ca stiva: telefon legat de nimeni, modul de împerechere pornit.
        send("IS");
        send("MWV4.1.5");
        send("II");
        loop();
    }

    private static void openPty() throws Exception {
        master = Os.open("/dev/ptmx", OsConstants.O_RDWR | OsConstants.O_NOCTTY, 0);
        ioctl(TIOCSPTLCK, 0);
        int n = ioctl(TIOCGPTN, 0);
        String slave = "/dev/pts/" + n;
        Os.chmod(slave, 0777);
        new File(LINK).delete();
        Os.symlink(slave, LINK);
        note("pty " + slave + " -> " + LINK);
    }

    /** Os.ioctlInt e ascunsă pe API 27 și primește un android.util.MutableInt. */
    private static int ioctl(int cmd, int value) throws Exception {
        Class<?> holder = Class.forName("android.util.MutableInt");
        Object arg = holder.getConstructor(int.class).newInstance(value);
        Method m = Os.class.getMethod("ioctlInt", FileDescriptor.class, int.class, holder);
        m.invoke(null, master, cmd, arg);
        return holder.getField("value").getInt(arg);
    }

    private static void loop() throws Exception {
        StructPollfd[] poll = {new StructPollfd()};
        poll[0].fd = master;
        poll[0].events = (short) OsConstants.POLLIN;
        byte[] buf = new byte[512];
        StringBuilder line = new StringBuilder();
        while (true) {
            int ready = Os.poll(poll, 500);
            if (ready == 0) continue;
            int n;
            try {
                n = Os.read(master, buf, 0, buf.length);
            } catch (ErrnoException e) {
                // Niciun capăt de slave deschis: launcher-ul încă n-a venit.
                if (e.errno == OsConstants.EIO) {
                    Thread.sleep(200);
                    continue;
                }
                throw e;
            }
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xff);
                if (c == '\r' || c == '\n') {
                    if (line.length() > 0) handle(line.toString());
                    line.setLength(0);
                } else {
                    line.append(c);
                }
            }
        }
    }

    private static int profile = 1192;

    private static void handle(String raw) {
        // Configurarea stivei; ca pe unitate, răspunsul n-are „+" în față.
        if (raw.startsWith("AT+")) {
            clientSeen = true;
            note("<< " + raw);
            if (raw.startsWith("AT+PROFILE")) {
                profileCommand(raw.substring(10));
            } else if (raw.equals("AT+REBOOT")) {
                // cmdReboot din blueware: doar răspunde OK, de două ori.
                send("OK");
                send("OK");
            } else {
                send("OK");
            }
            return;
        }
        if (!raw.startsWith("AT#")) {
            // Tot ce am scris noi și se întoarce: pty-ul face ecou, deci nu e raw.
            // Înainte de prima comandă e normal: clientul încă n-a pus modul raw.
            note((clientSeen ? "ECHO " : "(ecou înainte de client) ") + raw);
            return;
        }
        clientSeen = true;
        String text = new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        note("<< " + text);
        String cmd = text.substring(3, Math.min(5, text.length()));
        String arg = text.length() > 5 ? text.substring(5) : "";
        switch (cmd) {
            case "MM":
                if (!arg.isEmpty()) name = arg;
                send("MM" + name);
                send("OK");
                break;
            case "MN":
                if (!arg.isEmpty()) pin = arg;
                send("MN" + pin);
                send("OK");
                break;
            case "MV":
                send("MU" + av);
                send("OK");
                break;
            case "MX":
                send("MX1" + ADDR + PHONE);
                send("OK");
                break;
            case "MH":
            case "MF":
                send("MF00");
                send("OK");
                break;
            case "MZ":
                send("MZ000D18A179B5");
                send("OK");
                break;
            case "VA":
            case "VB":
                send("OK");
                break;
            case "MI":
                connect();
                break;
            case "MJ":
                send("OK");
                drop();
                break;
            case "MA":
                send("OK");
                toggle();
                break;
            case "MD":
                send("OK");
                skip(1);
                break;
            case "ME":
                send("OK");
                skip(-1);
                break;
            case "DA":
            case "DV":
                send("OK");
                send("II");
                break;
            case "DB":
                send("OK");
                send("IJ");
                break;
            default:
                note("FORBIDDEN " + text);
                send("ERROR");
                break;
        }
    }

    /** Ca cmdProfilesToggle: doar cifre, fără „=", iar o schimbare rupe legătura. */
    private static void profileCommand(String arg) {
        if (arg.isEmpty()) {
            send("PROFILE" + profile);
            send("OK");
            return;
        }
        if (!arg.matches("\\d+") || Integer.parseInt(arg) == profile) {
            send("ERROR");
            return;
        }
        profile = Integer.parseInt(arg) & 1192;
        if (av != 1) drop();
        send("OK");
    }

    private static void connect() {
        // Ca pe unitate: al doilea MI cât primul e în curs primește ERROR.
        if (av == 2) {
            send("ERROR");
            return;
        }
        if (av >= 3) {
            send("OK");
            return;
        }
        av = 2;
        send("OK");
        send("MU2");
        later(1200, FakeGoc::linked);
    }

    private static void linked() {
        av = 3;
        send("MA");
        send("MU3");
    }

    private static void toggle() {
        if (av == 5) {
            av = 4;
            send("MA");
            send("MU4");
            return;
        }
        av = 5;
        send("MB");
        send("MU5");
        send("MB");
        later(600, FakeGoc::sendTrack);
    }

    private static void skip(int delta) {
        track = (track + delta + TRACKS.length) % TRACKS.length;
        later(300, FakeGoc::sendTrack);
    }

    /** MI cu separatori 0xFF: octeți bruți, nu text. */
    private static void sendTrack() {
        String[] t = TRACKS[track];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] code = "MI".getBytes(StandardCharsets.US_ASCII);
        out.write(code, 0, code.length);
        String[] fields = {t[0], t[1], t[2], String.valueOf(track + 1), t[3]};
        for (String f : fields) {
            byte[] b = f.getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            out.write(0xff);
        }
        out.write('\r');
        out.write('\n');
        note(">> MI " + String.join(" | ", fields));
        write(out.toByteArray());
    }

    private static void drop() {
        av = 1;
        send("MY");
        send("IA");
        send("II");
        send("MU1");
        send("MG1");
    }

    private static void startControl() throws Exception {
        new File(CTL).delete();
        Os.mkfifo(CTL, 0666);
        Thread t = new Thread(() -> {
            while (true) {
                try (BufferedReader r = new BufferedReader(new FileReader(CTL))) {
                    String line;
                    while ((line = r.readLine()) != null) control(line.trim());
                } catch (Exception e) {
                    note("ctl " + e);
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void control(String what) {
        note("-- " + what);
        switch (what) {
            case "drop":
                drop();
                break;
            case "phone":
                send("IV");
                av = 2;
                send("MU2");
                later(500, FakeGoc::linked);
                break;
            case "paired":
                send("IJ1");
                av = 3;
                send("MA");
                send("MU3");
                break;
            case "hfp":
                send("IB");
                send("MX0" + ADDR + PHONE);
                send("MG3");
                break;
            case "poll":
                for (int i = 0; i < 5; i++) send("MG1");
                break;
            case "die":
                System.exit(0);
                break;
            default:
                note("ctl necunoscut: " + what);
                break;
        }
    }

    private static void later(long ms, Runnable r) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
                return;
            }
            r.run();
        });
        t.setDaemon(true);
        t.start();
    }

    private static void send(String line) {
        note(">> " + line);
        write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static synchronized void write(byte[] bytes) {
        try {
            int off = 0;
            while (off < bytes.length) off += Os.write(master, bytes, off, bytes.length - off);
        } catch (Exception e) {
            note("write " + e);
        }
    }

    private static synchronized void note(String text) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        log.println(stamp + " " + text);
    }
}
