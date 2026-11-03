package com.jarvis.mobile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Comunicacao segura com o notebook (via ntfy) e Wake-on-LAN.
 * Toda mensagem e assinada com HMAC-SHA256 usando o segredo do pareamento.
 * Formato: v1|timestamp|nonce|base64url(tipo:conteudo)|hmac
 */
final class Remoto {
    private Remoto() {}

    static final class Pareamento {
        String servidor = "https://ntfy.sh";
        String topico = "";
        String segredo = "";
        List<String> macs = new ArrayList<>();
    }

    static final class Msg {
        String tipo = "";
        String conteudo = "";
        String nonce = "";
    }

    static final class Ack {
        String re = "";
        boolean ok;
        String status = "";
        List<String> falas = new ArrayList<>();
    }

    private static final SecureRandom RNG = new SecureRandom();

    // ---------------------------------------------------------------
    // Pareamento: JARVIS1|servidor|topico|segredo|mac1,mac2
    // ---------------------------------------------------------------
    static Pareamento lerCodigo(String codigo) {
        if (codigo == null) return null;
        String[] p = codigo.trim().split("\\|", -1);
        if (p.length != 5 || !p[0].equals("JARVIS1")) return null;
        if (!p[1].matches("https?://[A-Za-z0-9.:\\-]+") || !p[2].matches("[A-Za-z0-9_\\-]{8,64}")
                || !p[3].matches("[A-Za-z0-9]{16,128}")) return null;
        Pareamento r = new Pareamento();
        r.servidor = p[1];
        r.topico = p[2];
        r.segredo = p[3];
        for (String mac : p[4].split(",")) {
            if (mac.trim().matches("(?i)([0-9a-f]{2}[:\\-]){5}[0-9a-f]{2}")) r.macs.add(mac.trim());
        }
        return r;
    }

    // ---------------------------------------------------------------
    // Assinatura
    // ---------------------------------------------------------------
    static String b64(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String desb64(String s) {
        return new String(java.util.Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    static String hmacHex(String segredo, String dados) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(dados.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String nonceAleatorio() {
        byte[] b = new byte[6];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** Devolve {mensagem, nonce}. */
    static String[] montar(String segredo, String tipo, String conteudo) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = nonceAleatorio();
        String base = "v1|" + ts + "|" + nonce + "|" + b64(tipo + ":" + conteudo);
        return new String[]{base + "|" + hmacHex(segredo, base), nonce};
    }

    /** Verifica assinatura, validade (5 min) e repeticao. Devolve null se for invalida. */
    static Msg verificar(String segredo, String texto, Set<String> vistos) {
        try {
            if (texto == null) return null;
            String[] p = texto.trim().split("\\|", -1);
            if (p.length != 5 || !p[0].equals("v1")) return null;
            String base = p[0] + "|" + p[1] + "|" + p[2] + "|" + p[3];
            byte[] esperado = hmacHex(segredo, base).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(esperado, p[4].toLowerCase().getBytes(StandardCharsets.UTF_8))) return null;
            long ts = Long.parseLong(p[1]);
            if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 300) return null;
            if (vistos != null) {
                synchronized (vistos) {
                    if (vistos.contains(p[2])) return null;
                    vistos.add(p[2]);
                    if (vistos instanceof LinkedHashSet && vistos.size() > 500) {
                        Iterator<String> it = vistos.iterator();
                        it.next();
                        it.remove();
                    }
                }
            }
            String corpo = desb64(p[3]);
            int i = corpo.indexOf(':');
            Msg m = new Msg();
            m.tipo = i < 0 ? corpo : corpo.substring(0, i);
            m.conteudo = i < 0 ? "" : corpo.substring(i + 1);
            m.nonce = p[2];
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // Resposta (ack): linhas "re=..", "ok=1", "status=..", "f=fala"
    static String montarAck(String re, boolean ok, String status, List<String> falas) {
        StringBuilder sb = new StringBuilder("re=").append(re).append("\nok=").append(ok ? 1 : 0);
        if (status != null && !status.isEmpty()) sb.append("\nstatus=").append(status.replace('\n', ' '));
        for (String f : falas) sb.append("\nf=").append(f.replace('\n', ' '));
        return sb.toString();
    }

    static Ack lerAck(String conteudo) {
        Ack a = new Ack();
        for (String linha : conteudo.split("\n")) {
            if (linha.startsWith("re=")) a.re = linha.substring(3);
            else if (linha.startsWith("ok=")) a.ok = linha.substring(3).equals("1");
            else if (linha.startsWith("status=")) a.status = linha.substring(7);
            else if (linha.startsWith("f=")) a.falas.add(linha.substring(2));
        }
        return a;
    }

    // ---------------------------------------------------------------
    // ntfy (HTTP)
    // ---------------------------------------------------------------
    private static String base(String servidor) {
        return servidor.endsWith("/") ? servidor.substring(0, servidor.length() - 1) : servidor;
    }

    static void publicar(String servidor, String topico, String corpo) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base(servidor) + "/" + topico).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("User-Agent", "JarvisMobile/1.0");
            byte[] dados = corpo.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(dados);
            }
            int codigo = c.getResponseCode();
            if (codigo < 200 || codigo >= 300) throw new IOException("HTTP " + codigo);
            try (InputStream is = c.getInputStream()) {
                while (is.read() != -1) { /* descarta */ }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Mensagens recentes do topico (ex.: since = "2m"). */
    static List<String> buscar(String servidor, String topico, String since) throws IOException {
        List<String> saida = new ArrayList<>();
        HttpURLConnection c = (HttpURLConnection) new URL(
            base(servidor) + "/" + topico + "/json?poll=1&since=" + since).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "JarvisMobile/1.0");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String linha;
                while ((linha = r.readLine()) != null) {
                    if (!"message".equals(campoJson(linha, "event"))) continue;
                    String msg = campoJson(linha, "message");
                    if (msg != null) saida.add(msg);
                }
            }
        } finally {
            c.disconnect();
        }
        return saida;
    }

    /** Fica ouvindo o topico (para o modo Base). Reconecta sozinho. */
    static void escutar(String servidor, String topico, Consumer<String> aoMensagem, AtomicBoolean parar) {
        String ultimoId = null;
        long espera = 3000;
        while (!parar.get()) {
            HttpURLConnection c = null;
            try {
                String url = base(servidor) + "/" + topico + "/json" + (ultimoId != null ? "?since=" + ultimoId : "");
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(120000);
                c.setRequestProperty("User-Agent", "JarvisMobile/1.0");
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    espera = 3000;
                    String linha;
                    while (!parar.get() && (linha = r.readLine()) != null) {
                        if (!"message".equals(campoJson(linha, "event"))) continue;
                        String id = campoJson(linha, "id");
                        if (id != null) ultimoId = id;
                        String msg = campoJson(linha, "message");
                        if (msg != null) aoMensagem.accept(msg);
                    }
                }
            } catch (Exception e) {
                try { Thread.sleep(espera); } catch (InterruptedException ie) { return; }
                espera = Math.min(espera * 2, 60000);
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    /** Le um campo de texto de uma linha JSON simples. */
    static String campoJson(String json, String campo) {
        String chave = "\"" + campo + "\":";
        int p = json.indexOf(chave);
        if (p < 0) return null;
        p += chave.length();
        while (p < json.length() && json.charAt(p) == ' ') p++;
        if (p >= json.length() || json.charAt(p) != '"') return null;
        p++;
        StringBuilder sb = new StringBuilder();
        while (p < json.length()) {
            char c = json.charAt(p++);
            if (c == '"') return sb.toString();
            if (c == '\\' && p < json.length()) {
                char d = json.charAt(p++);
                switch (d) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'u':
                        if (p + 4 <= json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(p, p + 4), 16));
                            p += 4;
                        }
                        break;
                    default: sb.append(d);
                }
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Wake-on-LAN
    // ---------------------------------------------------------------
    static byte[] pacoteWol(String mac) {
        String[] partes = mac.trim().split("[:\\-]");
        if (partes.length != 6) return null;
        byte[] endereco = new byte[6];
        try {
            for (int i = 0; i < 6; i++) endereco[i] = (byte) Integer.parseInt(partes[i], 16);
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] pacote = new byte[102];
        Arrays.fill(pacote, 0, 6, (byte) 0xFF);
        for (int i = 0; i < 16; i++) System.arraycopy(endereco, 0, pacote, 6 + i * 6, 6);
        return pacote;
    }

    static void enviarWol(List<String> macs, List<InetAddress> destinos) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            for (String mac : macs) {
                byte[] p = pacoteWol(mac);
                if (p == null) continue;
                for (InetAddress d : destinos) {
                    for (int porta : new int[]{9, 7}) {
                        try {
                            s.send(new DatagramPacket(p, p.length, d, porta));
                        } catch (IOException ignorado) { /* tenta o proximo */ }
                    }
                }
            }
        } catch (Exception ignorado) { /* sem rede */ }
    }
}
