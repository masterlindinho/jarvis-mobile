package com.jarvis.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 1;

    private SharedPreferences prefs;
    private final ExecutorService exec = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private TextView status;
    private TextView logView;
    private ScrollView scroll;
    private Button btnCont;
    private final StringBuilder logTexto = new StringBuilder();

    private SpeechRecognizer recognizer;
    private boolean usarOnDevice = true;
    private int falhasSeguidas = 0;
    private volatile boolean escutando = false;
    private volatile boolean ocupado = false;
    private volatile boolean ativo = false;
    private boolean continuo;
    private boolean permissaoOk = false;
    private boolean saudou = false;

    private TextToSpeech tts;
    private volatile boolean ttsPronto = false;
    private final AtomicInteger falasPendentes = new AtomicInteger();

    private Remoto.Pareamento par;
    private final Set<String> vistos = new LinkedHashSet<>();
    private boolean modoBase;
    private final AtomicBoolean pararBase = new AtomicBoolean(false);

    private Map<String, String> appsPkg;
    private Map<String, String> appsNome;

    // ---------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        continuo = prefs.getBoolean("continuo", true);
        modoBase = prefs.getBoolean("base", false);
        par = Remoto.lerCodigo(prefs.getString("codigo", ""));
        construirUi();
        if (modoBase) {
            iniciarBase();
        } else {
            iniciarTts();
            pedirPermissao();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ativo = true;
        if (!modoBase && permissaoOk && ttsPronto && saudou) ui.postDelayed(this::retomar, 600);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ativo = false;
        escutando = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignorado) { }
        }
    }

    @Override
    protected void onDestroy() {
        pararBase.set(true);
        exec.shutdownNow();
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignorado) { }
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------
    // Interface
    // ---------------------------------------------------------------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void construirUi() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(0xFF0A1420);
        raiz.setPadding(dp(20), dp(40), dp(20), dp(20));

        TextView titulo = new TextView(this);
        titulo.setText(modoBase ? "JARVIS · BASE" : "JARVIS");
        titulo.setTextColor(0xFF00C8FF);
        titulo.setTextSize(30);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        raiz.addView(titulo);

        status = new TextView(this);
        status.setTextColor(0xFF9FB3C8);
        status.setTextSize(16);
        status.setPadding(0, dp(4), 0, dp(12));
        status.setText("Iniciando...");
        raiz.addView(status);

        logView = new TextView(this);
        logView.setTextColor(0xFFE6EEF7);
        logView.setTextSize(15);
        scroll = new ScrollView(this);
        scroll.addView(logView);
        raiz.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (!modoBase) {
            Button mic = new Button(this);
            mic.setText("FALAR");
            mic.setTextSize(20);
            mic.setOnClickListener(v -> aoTocarMic());
            raiz.addView(mic, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(68)));
        }

        LinearLayout linha = new LinearLayout(this);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        linha.setGravity(Gravity.CENTER);
        if (!modoBase) {
            btnCont = new Button(this);
            atualizarBotaoContinuo();
            btnCont.setOnClickListener(v -> {
                continuo = !continuo;
                prefs.edit().putBoolean("continuo", continuo).apply();
                atualizarBotaoContinuo();
                if (continuo) retomar();
            });
            linha.addView(btnCont, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        Button cfg = new Button(this);
        cfg.setText("Configurações");
        cfg.setOnClickListener(v -> mostrarConfig());
        linha.addView(cfg, new LinearLayout.LayoutParams(0, dp(52), 1f));
        raiz.addView(linha);

        setContentView(raiz);
    }

    private void atualizarBotaoContinuo() {
        if (btnCont != null) btnCont.setText(continuo ? "Contínuo: ligado" : "Contínuo: desligado");
    }

    private void status(String s) {
        ui.post(() -> status.setText(s));
    }

    private void log(String s) {
        ui.post(() -> {
            logTexto.append(s).append("\n\n");
            if (logTexto.length() > 6000) logTexto.delete(0, logTexto.length() - 5000);
            logView.setText(logTexto.toString());
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void aviso(String s) {
        log("⚠ " + s);
        status(s);
    }

    // ---------------------------------------------------------------
    // Voz (fala)
    // ---------------------------------------------------------------
    private void iniciarTts() {
        tts = new TextToSpeech(this, st -> {
            if (st != TextToSpeech.SUCCESS) {
                aviso("Este celular não tem voz instalada (Texto para fala).");
                ttsPronto = false;
                ui.post(this::tentarComecar);
                return;
            }
            int r = tts.setLanguage(new Locale("pt", "BR"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                aviso("Voz em português não instalada. Instale em Configurações > Idioma > Saída de texto para fala.");
            }
            tts.setPitch(0.85f);
            tts.setSpeechRate(1.0f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { fimFala(); }
                @Override public void onError(String id) { fimFala(); }
            });
            ttsPronto = true;
            ui.post(this::tentarComecar);
        });
    }

    private void dizer(String texto) {
        log("Jarvis: " + texto);
        if (!ttsPronto) return;
        falasPendentes.incrementAndGet();
        int r = tts.speak(texto, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime());
        if (r != TextToSpeech.SUCCESS) fimFala();
    }

    private void fimFala() {
        if (falasPendentes.decrementAndGet() <= 0) {
            falasPendentes.set(0);
            ui.post(this::retomar);
        }
    }

    // ---------------------------------------------------------------
    // Reconhecimento de voz (offline quando o celular permite)
    // ---------------------------------------------------------------
    private void pedirPermissao() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissaoOk = true;
            tentarComecar();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_AUDIO && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            permissaoOk = true;
            tentarComecar();
        } else {
            aviso("Sem permissão do microfone. Ative nas configurações do aplicativo.");
        }
    }

    private void tentarComecar() {
        if (permissaoOk && !saudou) {
            saudou = true;
            String s = par == null
                ? "Jarvis online, senhor. Para controlar o notebook, abra as configurações e cole o código de pareamento."
                : "Jarvis online, senhor.";
            if (ttsPronto) dizer(s); else { log("Jarvis: " + s); retomar(); }
        }
    }

    private void criarReconhecedor() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignorado) { }
            recognizer = null;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && usarOnDevice
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        }
        if (recognizer == null) return;
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { status("Ouvindo..."); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { status("Processando..."); }
            @Override public void onPartialResults(Bundle p) { }
            @Override public void onEvent(int t, Bundle p) { }
            @Override public void onError(int erro) {
                escutando = false;
                tratarErro(erro);
            }
            @Override public void onResults(Bundle r) {
                escutando = false;
                falhasSeguidas = 0;
                ArrayList<String> lista = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (lista == null || lista.isEmpty()) {
                    retomar();
                    return;
                }
                processar(melhor(lista));
            }
        });
    }

    private String melhor(List<String> lista) {
        for (String t : lista) {
            for (String p : Comandos.normalizar(t).split(" ")) {
                if (Comandos.ehJarvis(p)) return t;
            }
        }
        return lista.get(0);
    }

    private void aoTocarMic() {
        if (!permissaoOk) { pedirPermissao(); return; }
        if (ocupado || falasPendentes.get() > 0) return;
        iniciarEscuta();
    }

    private void retomar() {
        if (!ativo || modoBase || ocupado || escutando || falasPendentes.get() > 0) return;
        if (!permissaoOk) return;
        if (continuo) iniciarEscuta();
        else status("Toque em FALAR");
    }

    private void iniciarEscuta() {
        if (!ativo || modoBase || escutando || ocupado || falasPendentes.get() > 0) return;
        if (recognizer == null) criarReconhecedor();
        if (recognizer == null) {
            aviso("Reconhecimento de voz indisponível neste celular.");
            return;
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        escutando = true;
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            escutando = false;
            recognizer = null;
            ui.postDelayed(this::iniciarEscuta, 800);
        }
    }

    private void tratarErro(int erro) {
        switch (erro) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                if (continuo) ui.postDelayed(this::iniciarEscuta, 400);
                else status("Toque em FALAR");
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                criarReconhecedor();
                ui.postDelayed(this::iniciarEscuta, 900);
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                permissaoOk = false;
                pedirPermissao();
                break;
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                if (usarOnDevice) {
                    usarOnDevice = false;
                    criarReconhecedor();
                    ui.postDelayed(this::iniciarEscuta, 300);
                } else {
                    aviso("Português offline indisponível. Baixe o pacote 'Português (Brasil)' no reconhecimento de voz offline do Google.");
                }
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                aviso("Sem internet e sem pacote de voz offline. Baixe o reconhecimento offline em Português (Brasil) nas configurações do Google.");
                break;
            default:
                falhasSeguidas++;
                if (falhasSeguidas < 4 && continuo) {
                    criarReconhecedor();
                    ui.postDelayed(this::iniciarEscuta, 1000);
                } else {
                    status("Toque em FALAR");
                }
        }
    }

    // ---------------------------------------------------------------
    // Execucao dos comandos
    // ---------------------------------------------------------------
    private void processar(String texto) {
        log("Você: " + texto);
        Comandos.Acao a = Comandos.interpretar(texto);
        if (a.tipo == Comandos.Tipo.NADA) {
            retomar();
            return;
        }
        ocupado = true;
        status("Executando...");
        exec.execute(() -> {
            try {
                tratar(a);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                dizer("Houve um erro, senhor.");
            } finally {
                ocupado = false;
                if (falasPendentes.get() == 0) ui.post(this::retomar);
            }
        });
    }

    private void tratar(Comandos.Acao a) throws InterruptedException {
        switch (a.tipo) {
            case HORA: dizer(horaPorExtenso()); break;
            case DATA: dizer(dataPorExtenso()); break;
            case PIADA: dizer(Comandos.PIADAS[random.nextInt(Comandos.PIADAS.length)]); break;
            case AJUDA: dizer(Comandos.AJUDA); break;
            case CALCULO: dizer(a.texto); break;
            case TEMPORIZADOR: temporizador(a); break;
            case LANTERNA:
                if (lanterna(a.flag)) dizer(a.flag ? "Lanterna ligada, senhor." : "Lanterna desligada, senhor.");
                else dizer("Não encontrei lanterna neste celular, senhor.");
                break;
            case VOLUME: volume(a); break;
            case MUDO: mudo(a.flag); break;
            case ABRIR_APP: abrirApp(a.texto); break;
            case PESQUISA: pesquisar(a); break;
            case LIGAR_NOTEBOOK: ligarNotebook(a); break;
            case STATUS_NOTEBOOK: statusNotebook(); break;
            case ENVIAR_PC: if (!precisaPar()) enviarAoPc(a.texto); break;
            default: break;
        }
    }

    private String horaPorExtenso() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        String ini = (h == 1 || h == 13) ? "É uma hora" : "São " + h + " horas";
        if (m == 0) return ini + " em ponto, senhor.";
        return ini + " e " + m + (m == 1 ? " minuto" : " minutos") + ", senhor.";
    }

    private String dataPorExtenso() {
        Calendar c = Calendar.getInstance();
        return "Hoje é " + Comandos.DIAS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH)
            + " de " + Comandos.MESES[c.get(Calendar.MONTH)] + " de " + c.get(Calendar.YEAR) + ", senhor.";
    }

    private void temporizador(Comandos.Acao a) {
        if (a.numero <= 0) {
            dizer("Por quanto tempo, senhor?");
            return;
        }
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, (int) a.numero)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!a.texto.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.texto);
        try {
            startActivity(i);
            dizer("Certo, senhor. Avisarei em " + Comandos.formatarDuracao(a.numero) + ".");
        } catch (ActivityNotFoundException | SecurityException e) {
            dizer("Não consegui criar o temporizador neste celular, senhor.");
        }
    }

    private boolean lanterna(boolean ligar) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    cm.setTorchMode(id, ligar);
                    return true;
                }
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            return false;
        }
        return false;
    }

    private void volume(Comandos.Acao a) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (a.texto.equals("definir")) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * a.numero / 100f), AudioManager.FLAG_SHOW_UI);
            dizer("Volume em " + a.numero + " por cento, senhor.");
        } else {
            int dir = a.texto.equals("subir") ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
            for (int i = 0; i < a.numero; i++) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
            dizer("Volume ajustado, senhor.");
        }
    }

    private void mudo(boolean silenciar) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            silenciar ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
        dizer(silenciar ? "Sem som, senhor." : "Som restaurado, senhor.");
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    private void pesquisar(Comandos.Acao a) {
        if (a.texto.isEmpty()) {
            dizer("O que devo pesquisar, senhor?");
            return;
        }
        if (!online()) {
            dizer("Sem internet para pesquisar, senhor.");
            return;
        }
        try {
            String q = URLEncoder.encode(a.texto, "UTF-8");
            String url = a.flag ? "https://www.youtube.com/results?search_query=" + q
                                : "https://www.google.com/search?q=" + q;
            ui.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    aviso("Nenhum navegador encontrado.");
                }
            });
            dizer("Pesquisando, senhor.");
        } catch (java.io.UnsupportedEncodingException e) {
            dizer("Não consegui pesquisar, senhor.");
        }
    }

    // ---------------------------------------------------------------
    // Aplicativos do celular
    // ---------------------------------------------------------------
    private synchronized void carregarApps() {
        if (appsPkg != null) return;
        appsPkg = new HashMap<>();
        appsNome = new HashMap<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String nome = String.valueOf(ri.loadLabel(pm));
            String chave = Comandos.normalizar(nome);
            if (!chave.isEmpty()) {
                appsPkg.put(chave, ri.activityInfo.packageName);
                appsNome.put(chave, nome);
            }
        }
    }

    private void abrirApp(String nome) throws InterruptedException {
        carregarApps();
        String chave = Comandos.melhorApp(nome, appsPkg.keySet());
        if (chave != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(appsPkg.get(chave));
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ui.post(() -> {
                    try { startActivity(i); } catch (Exception e) { aviso("Não consegui abrir."); }
                });
                dizer("Sim, senhor. Abrindo " + appsNome.get(chave) + ".");
                return;
            }
        }
        if (par != null && online()) {
            dizer("Não encontrei no celular. Enviando ao notebook, senhor.");
            enviarAoPc("abra " + nome);
        } else {
            dizer("Não encontrei esse aplicativo no celular, senhor.");
        }
    }

    // ---------------------------------------------------------------
    // Notebook
    // ---------------------------------------------------------------
    private boolean precisaPar() {
        if (par != null) return false;
        dizer("Preciso ser pareado com o notebook, senhor. Abra as configurações e cole o código.");
        ui.post(this::mostrarConfig);
        return true;
    }

    private Remoto.Ack aguardarAck(String nonce, int segundos) throws InterruptedException {
        long fim = System.currentTimeMillis() + segundos * 1000L;
        while (System.currentTimeMillis() < fim) {
            try {
                for (String m : Remoto.buscar(par.servidor, par.topico + "-r", "2m")) {
                    Remoto.Msg v = Remoto.verificar(par.segredo, m, null);
                    if (v != null && v.tipo.equals("A")) {
                        Remoto.Ack ack = Remoto.lerAck(v.conteudo);
                        if (ack.re.equals(nonce)) return ack;
                    }
                }
            } catch (IOException e) {
                // sem rede neste instante; tenta de novo
            }
            Thread.sleep(1500);
        }
        return null;
    }

    private boolean pingar(int segundos) throws InterruptedException {
        try {
            String[] m = Remoto.montar(par.segredo, "P", "");
            Remoto.publicar(par.servidor, par.topico, m[0]);
            return aguardarAck(m[1], segundos) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private void enviarAoPc(String comando) throws InterruptedException {
        if (!online()) {
            dizer("Sem internet no celular, senhor.");
            return;
        }
        try {
            String[] m = Remoto.montar(par.segredo, "C", comando);
            Remoto.publicar(par.servidor, par.topico, m[0]);
            Remoto.Ack ack = aguardarAck(m[1], 30);
            if (ack == null) {
                dizer("O notebook não respondeu, senhor. Ele pode estar desligado.");
            } else if (ack.falas.isEmpty()) {
                dizer(ack.ok ? "Feito, senhor." : "O notebook não conseguiu executar, senhor.");
            } else {
                for (String f : ack.falas) dizer(f);
            }
        } catch (IOException e) {
            dizer("Não consegui falar com o notebook, senhor.");
        }
    }

    private List<InetAddress> destinosBroadcast() {
        List<InetAddress> l = new ArrayList<>();
        try {
            l.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignorado) { }
        try {
            WifiManager w = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = w.getDhcpInfo();
            if (d != null && d.ipAddress != 0 && d.netmask != 0) {
                int bc = (d.ipAddress & d.netmask) | ~d.netmask;
                byte[] q = {(byte) (bc & 0xff), (byte) ((bc >> 8) & 0xff), (byte) ((bc >> 16) & 0xff), (byte) ((bc >> 24) & 0xff)};
                l.add(InetAddress.getByAddress(q));
            }
        } catch (Exception ignorado) { }
        return l;
    }

    private void acordar() {
        Remoto.enviarWol(par.macs, destinosBroadcast());
        try {
            String[] m = Remoto.montar(par.segredo, "W", "go");
            Remoto.publicar(par.servidor, par.topico, m[0]);
        } catch (IOException e) {
            log("(sem internet para avisar a base)");
        }
    }

    private void statusNotebook() throws InterruptedException {
        if (precisaPar()) return;
        if (!online()) { dizer("Sem internet no celular, senhor."); return; }
        dizer(pingar(10) ? "O notebook está ligado e online, senhor." : "O notebook não respondeu, senhor.");
    }

    private void ligarNotebook(Comandos.Acao a) throws InterruptedException {
        if (precisaPar()) return;
        if (par.macs.isEmpty()) {
            dizer("O código de pareamento não tem o endereço do notebook, senhor.");
            return;
        }
        if (!online()) {
            acordar();
            dizer("Sem internet, senhor. Enviei o sinal apenas pela rede local.");
            return;
        }
        if (pingar(7)) {
            dizer("O notebook já está ligado, senhor.");
            if (!a.extra.isEmpty()) enviarAoPc(a.extra);
            return;
        }
        dizer("Enviando o sinal para ligar o notebook, senhor.");
        acordar();
        long inicio = System.currentTimeMillis();
        long ultimoSinal = inicio;
        boolean avisou = false;
        boolean noAr = false;
        while (System.currentTimeMillis() - inicio < 6 * 60_000L) {
            if (pingar(8)) { noAr = true; break; }
            long agora = System.currentTimeMillis();
            if (agora - ultimoSinal > 45_000L) { acordar(); ultimoSinal = agora; }
            if (!avisou && agora - inicio > 90_000L) {
                dizer("Ainda aguardando o notebook iniciar, senhor.");
                avisou = true;
            }
            Thread.sleep(4000);
        }
        if (!noAr) {
            dizer("O notebook não respondeu, senhor. Verifique se o Wake-on-LAN está ativado e se ele está no cabo de rede.");
            return;
        }
        dizer("Notebook online, senhor.");
        if (!a.extra.isEmpty()) enviarAoPc(a.extra);
    }

    // ---------------------------------------------------------------
    // Modo Base (celular antigo em casa, so para enviar o sinal de ligar)
    // ---------------------------------------------------------------
    private void iniciarBase() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.05f;
        getWindow().setAttributes(lp);
        if (par == null) {
            status("Modo Base: abra as configurações e cole o código de pareamento.");
            log("Modo Base: cole o código de pareamento nas configurações.");
            return;
        }
        status("Base ativa. Deixe carregando, no Wi-Fi de casa, com a tela ligada.");
        log("Base ativa. Aguardando pedidos para ligar o notebook...");
        exec.execute(() -> Remoto.escutar(par.servidor, par.topico, this::aoMensagemBase, pararBase));
    }

    private void aoMensagemBase(String texto) {
        Remoto.Msg m = Remoto.verificar(par.segredo, texto, vistos);
        if (m == null || !m.tipo.equals("W")) return;
        Remoto.enviarWol(par.macs, destinosBroadcast());
        log("Sinal de ligar enviado ao notebook.");
        try {
            String[] ack = Remoto.montar(par.segredo, "A", Remoto.montarAck(m.nonce, true, "base",
                Arrays.asList("Sinal de ligar enviado pela base, senhor.")));
            Remoto.publicar(par.servidor, par.topico + "-r", ack[0]);
        } catch (IOException e) {
            log("(não consegui confirmar o envio)");
        }
    }

    // ---------------------------------------------------------------
    // Configuracoes
    // ---------------------------------------------------------------
    private void mostrarConfig() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(10), dp(20), dp(0));

        TextView info = new TextView(this);
        info.setText(par == null
            ? "Não pareado. No notebook: ícone do Jarvis > Parear com o celular. Depois cole o código aqui."
            : "Pareado (tópico " + par.topico.substring(0, 6) + "…, " + par.macs.size() + " endereço(s) MAC).");
        l.addView(info);

        EditText campo = new EditText(this);
        campo.setHint("Código de pareamento");
        campo.setMinLines(2);
        l.addView(campo);

        Button colar = new Button(this);
        colar.setText("Colar da área de transferência");
        l.addView(colar);
        Button salvar = new Button(this);
        salvar.setText("Salvar código");
        l.addView(salvar);
        Button testar = new Button(this);
        testar.setText("Testar conexão com o notebook");
        l.addView(testar);
        Button sinal = new Button(this);
        sinal.setText("Enviar sinal de ligar (teste)");
        l.addView(sinal);
        Button modo = new Button(this);
        modo.setText(modoBase ? "Trocar para modo CONTROLE" : "Trocar para modo BASE (celular de casa)");
        l.addView(modo);

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Configurações").setView(sv)
            .setNegativeButton("Fechar", null).create();

        colar.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0) campo.setText(d.getItemAt(0).coerceToText(this).toString().trim());
            else Toast.makeText(this, "Área de transferência vazia", Toast.LENGTH_SHORT).show();
        });
        salvar.setOnClickListener(v -> {
            String txt = campo.getText().toString().trim();
            Remoto.Pareamento p = Remoto.lerCodigo(txt);
            if (p == null) {
                Toast.makeText(this, "Código inválido", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString("codigo", txt).apply();
            par = p;
            Toast.makeText(this, "Pareado com sucesso!", Toast.LENGTH_LONG).show();
            dlg.dismiss();
            if (modoBase) recreate();
        });
        testar.setOnClickListener(v -> {
            if (par == null) { Toast.makeText(this, "Cole e salve o código primeiro", Toast.LENGTH_LONG).show(); return; }
            dlg.dismiss();
            exec.execute(() -> {
                try { statusNotebook(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        });
        sinal.setOnClickListener(v -> {
            if (par == null) { Toast.makeText(this, "Cole e salve o código primeiro", Toast.LENGTH_LONG).show(); return; }
            dlg.dismiss();
            exec.execute(() -> {
                acordar();
                dizer("Sinal de ligar enviado, senhor.");
            });
        });
        modo.setOnClickListener(v -> {
            prefs.edit().putBoolean("base", !modoBase).apply();
            dlg.dismiss();
            recreate();
        });
        dlg.show();
    }
}
