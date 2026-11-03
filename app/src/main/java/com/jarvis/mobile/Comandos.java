package com.jarvis.mobile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interpreta frases em portugues. Nao usa nada do Android (pode ser testado em qualquer PC). */
final class Comandos {
    private Comandos() {}

    enum Tipo {
        LIGAR_NOTEBOOK, STATUS_NOTEBOOK, ENVIAR_PC, ABRIR_APP, HORA, DATA, CALCULO, TEMPORIZADOR,
        LANTERNA, VOLUME, MUDO, PIADA, AJUDA, PESQUISA, NADA
    }

    static final class Acao {
        final Tipo tipo;
        String texto = "";
        String extra = "";
        long numero;
        boolean flag;

        Acao(Tipo t) { tipo = t; }

        Acao texto(String s) { texto = s; return this; }
        Acao numero(long n) { numero = n; return this; }
        Acao flag(boolean f) { flag = f; return this; }

        @Override public String toString() {
            return tipo + "{texto='" + texto + "', extra='" + extra + "', numero=" + numero + ", flag=" + flag + "}";
        }
    }

    static final String[] PIADAS = {
        "Por que o computador foi ao médico? Porque estava com um vírus.",
        "O que o zero disse para o oito? Belo cinto.",
        "Por que o livro de matemática ficou triste? Porque tinha muitos problemas.",
        "Qual é o cúmulo da rapidez? Fechar a gaveta com a chave dentro."
    };
    static final String AJUDA =
        "Posso abrir aplicativos do celular, fazer contas, marcar temporizadores, ligar a lanterna, "
        + "controlar o volume, dizer a hora, pesquisar na internet, e controlar o seu notebook de longe: "
        + "ligar, abrir programas e jogos, senhor.";
    static final String[] DIAS = {"domingo", "segunda-feira", "terça-feira", "quarta-feira", "quinta-feira",
        "sexta-feira", "sábado"};
    static final String[] MESES = {"janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho",
        "agosto", "setembro", "outubro", "novembro", "dezembro"};

    // ---------------------------------------------------------------
    // Texto
    // ---------------------------------------------------------------
    static String normalizar(String s) {
        String t = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        Matcher m = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{3})+(?=\\D|$)").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, m.group().replace(".", ""));
        m.appendTail(sb);
        t = sb.toString();
        t = t.replaceAll("(\\d),(\\d)", "$1.$2");
        t = t.replace("%", " por cento ");
        t = t.replaceAll("[^\\w\\s.]", " ");
        t = t.replaceAll("(?<!\\d)\\.|\\.(?!\\d)", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int custo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + custo);
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    private static final Set<String> JARVIS_VAR = new HashSet<>(Arrays.asList(
        "jarvis", "jarbas", "jarves", "jarvi", "gervais", "jervis", "garvis", "jarvez", "jarbis", "charvis"));

    static boolean ehJarvis(String p) {
        return JARVIS_VAR.contains(p) || (p.length() >= 5 && levenshtein(p, "jarvis") <= 1);
    }

    private static String juntar(String[] t, int de, int ate) {
        StringBuilder sb = new StringBuilder();
        for (int i = de; i < ate && i < t.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t[i]);
        }
        return sb.toString();
    }

    static String removerAtivacao(String n) {
        String[] t = n.split(" ");
        for (int i = 0; i < t.length; i++) {
            if (ehJarvis(t[i])) {
                String depois = juntar(t, i + 1, t.length);
                if (!depois.isEmpty()) return depois;
                List<String> antes = new ArrayList<>();
                for (int k = 0; k < i; k++) {
                    if (!Arrays.asList("ei", "hey", "oi", "ola", "e").contains(t[k])) antes.add(t[k]);
                }
                return String.join(" ", antes);
            }
        }
        return n;
    }

    private static final Map<String, Integer> UNIDADES = new HashMap<>();
    private static final Map<String, Integer> DEZENAS = new HashMap<>();
    static {
        String[] u = {"zero", "um", "dois", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez",
            "onze", "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove"};
        for (int i = 0; i < u.length; i++) UNIDADES.put(u[i], i);
        UNIDADES.put("uma", 1);
        UNIDADES.put("duas", 2);
        UNIDADES.put("quatorze", 14);
        String[] d = {"vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa"};
        for (int i = 0; i < d.length; i++) DEZENAS.put(d[i], (i + 2) * 10);
        DEZENAS.put("cem", 100);
    }

    static String numerosPorExtenso(String n) {
        String[] t = n.split(" ");
        List<String> saida = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            String p = t[i];
            if (DEZENAS.containsKey(p)) {
                int v = DEZENAS.get(p);
                if (i + 2 < t.length && t[i + 1].equals("e") && UNIDADES.containsKey(t[i + 2])
                        && UNIDADES.get(t[i + 2]) >= 1 && UNIDADES.get(t[i + 2]) <= 9) {
                    v += UNIDADES.get(t[i + 2]);
                    i += 2;
                }
                saida.add(String.valueOf(v));
            } else if (UNIDADES.containsKey(p)) {
                saida.add(String.valueOf(UNIDADES.get(p)));
            } else {
                saida.add(p);
            }
        }
        return String.join(" ", saida);
    }

    // ---------------------------------------------------------------
    // Contas
    // ---------------------------------------------------------------
    private static final class Parser {
        final String s;
        int i = 0;

        Parser(String s) { this.s = s.replace(" ", ""); }

        double expr() {
            double v = termo();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '+') { i++; v += termo(); }
                else if (c == '-') { i++; v -= termo(); }
                else break;
            }
            return v;
        }

        double termo() {
            double v = fator();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '*') { i++; v *= fator(); }
                else if (c == '/') {
                    i++;
                    double d = fator();
                    if (d == 0) throw new ArithmeticException("divisao por zero");
                    v /= d;
                } else break;
            }
            return v;
        }

        double fator() {
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                char c = s.charAt(i++);
                double v = fator();
                return c == '-' ? -v : v;
            }
            double b = atomo();
            if (i + 1 < s.length() && s.charAt(i) == '*' && s.charAt(i + 1) == '*') {
                i += 2;
                double e = fator();
                if (Math.abs(e) > 100 || Math.abs(b) > 1e6) throw new IllegalArgumentException("grande demais");
                b = Math.pow(b, e);
            }
            return b;
        }

        double atomo() {
            if (i >= s.length()) throw new IllegalArgumentException("fim inesperado");
            if (s.charAt(i) == '(') {
                i++;
                double v = expr();
                if (i >= s.length() || s.charAt(i) != ')') throw new IllegalArgumentException("parenteses");
                i++;
                return v;
            }
            int j = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (j == i) throw new IllegalArgumentException("numero esperado");
            return Double.parseDouble(s.substring(j, i));
        }
    }

    static double avaliar(String expr) {
        Parser p = new Parser(expr);
        double v = p.expr();
        if (p.i != p.s.length()) throw new IllegalArgumentException("sobrou texto");
        return v;
    }

    static String formatarNumero(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String s = String.format(Locale.US, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', ',');
    }

    private static final Pattern CALC = Pattern.compile(
        "^(?:me diga |me fale )?(?:quanto e|quanto da|quanto vale|quanto fica|calcule|calcular|"
        + "qual e o resultado de|resultado de)\\s+(.+)$");

    /** Devolve a frase com o resultado, ou null se nao for uma conta. */
    static String calcular(String n) {
        Matcher m = CALC.matcher(n);
        if (!m.matches()) return null;
        String e = numerosPorExtenso(m.group(1));
        e = e.replaceAll("(\\d+(?:\\.\\d+)?)\\s*por cento\\s*de\\s*(\\d+(?:\\.\\d+)?)", "($1/100*$2)");
        e = e.replaceAll("raiz quadrada de (\\d+(?:\\.\\d+)?)", "($1**0.5)");
        e = e.replaceAll("\\bao quadrado\\b", "**2").replaceAll("\\bao cubo\\b", "**3")
             .replaceAll("\\belevado a\\b", "**").replaceAll("\\bpor cento\\b", "/100")
             .replaceAll("\\bmais\\b", "+").replaceAll("\\bmenos\\b", "-")
             .replaceAll("\\b(?:vezes|multiplicado por|multiplicar por|x)\\b", "*")
             .replaceAll("\\b(?:dividido por|dividido|divide por)\\b", "/");
        if (!e.matches("[\\d\\s+\\-*/().]+") || !e.matches(".*\\d.*") || !e.matches(".*[+\\-*/].*")) return null;
        try {
            return "O resultado é " + formatarNumero(avaliar(e)) + ", senhor.";
        } catch (ArithmeticException ex) {
            return "Não é possível dividir por zero, senhor.";
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Temporizadores
    // ---------------------------------------------------------------
    static long parseDuracao(String n) {
        String t = numerosPorExtenso(n).replaceAll("\\bmeia hora\\b", "30 minutos")
                .replaceAll("\\bum quarto de hora\\b", "15 minutos");
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(segundos?|minutos?|horas?)\\b").matcher(t);
        double total = 0;
        boolean achou = false;
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            String u = m.group(2);
            total += v * (u.startsWith("s") ? 1 : u.startsWith("m") ? 60 : 3600);
            achou = true;
        }
        return achou && total > 0 ? (long) total : 0;
    }

    static String formatarDuracao(long seg) {
        long h = seg / 3600, m = (seg % 3600) / 60, s = seg % 60;
        List<String> p = new ArrayList<>();
        if (h > 0) p.add(h + (h == 1 ? " hora" : " horas"));
        if (m > 0) p.add(m + (m == 1 ? " minuto" : " minutos"));
        if (s > 0) p.add(s + (s == 1 ? " segundo" : " segundos"));
        return p.isEmpty() ? "0 segundos" : String.join(" e ", p);
    }

    static String extrairMotivo(String n) {
        String nn = numerosPorExtenso(n);
        Matcher m = Pattern.compile("\\b(?:lembre|lembra|avise|avisa)\\s+(?:de |que |para |pra |sobre )?(.*)").matcher(nn);
        if (!m.find()) return "";
        String mot = m.group(1);
        mot = mot.replaceAll("\\b(?:daqui a|daqui|dentro de|em)\\s+\\d.*$", "");
        mot = mot.replaceAll("\\d+(?:\\.\\d+)?\\s*(?:segundos?|minutos?|horas?).*$", "");
        mot = mot.replaceAll("\\bmeia hora\\b.*$", "");
        return mot.trim();
    }

    // ---------------------------------------------------------------
    // Busca de aplicativos pelo nome falado
    // ---------------------------------------------------------------
    static double dice(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.length() < 2 || b.length() < 2) return 0;
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < a.length() - 1; i++) m.merge(a.substring(i, i + 2), 1, Integer::sum);
        int inter = 0;
        for (int i = 0; i < b.length() - 1; i++) {
            String bg = b.substring(i, i + 2);
            Integer c = m.get(bg);
            if (c != null && c > 0) { inter++; m.put(bg, c - 1); }
        }
        return 2.0 * inter / ((a.length() - 1) + (b.length() - 1));
    }

    static String melhorApp(String nome, Collection<String> chaves) {
        if (nome.length() < 3) return null;
        if (chaves.contains(nome)) return nome;
        String melhor = null;
        for (String k : chaves) if (k.contains(nome) && (melhor == null || k.length() < melhor.length())) melhor = k;
        if (melhor != null) return melhor;
        for (String k : chaves) {
            if (k.length() >= 4 && (" " + nome + " ").contains(" " + k + " ")
                    && (melhor == null || k.length() > melhor.length())) melhor = k;
        }
        if (melhor != null) return melhor;
        double melhorD = 0;
        for (String k : chaves) {
            double d = dice(nome, k);
            if (d > melhorD) { melhorD = d; melhor = k; }
        }
        return melhorD >= 0.75 ? melhor : null;
    }

    // ---------------------------------------------------------------
    // Interpretador principal
    // ---------------------------------------------------------------
    private static final String DISP = "(?:notebook|note book|laptop|computador|pc|windows|maquina|desktop)";
    private static final String ACAO_INICIAL = "(?:abra|abre|abrir|feche|fechar|encerre|inicie|iniciar|jogar|jogue|joga|"
        + "execute|rode|pause|pausar|continue|aumente|diminua|abaixe|suba|silencie|pesquise|pesquisar|procure|"
        + "busque|mostre|tire|toque|pule|volume|proxima|bloqueie|desligue|reinicie)";
    private static final String VERBO_PESQUISA =
        "(?:pesquis\\w*|procur\\w*|busc\\w*|mostr\\w*|achar|ache|encontre|encontrar|googl\\w+)";
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
        ("me uma um a o as os de do da dos das sobre por para pra pesquisa pesquisas busca buscas resultado "
        + "resultados no na ai favor pode poderia voce mim aqui e").split(" ")));
    private static final Set<String> ABRIR_FILLER = new HashSet<>(Arrays.asList(
        ("o a os as um uma aplicativo programa app jogo para pra mim por favor pode voce me la do da no na "
        + "meu minha").split(" ")));
    private static final String[] PERGUNTAS = {"quando", "quem", "qual", "quais", "quanto", "quantos", "quanta",
        "quantas", "como", "onde", "por que", "porque", "o que", "que", "existe", "sera", "me diga", "me fale",
        "explique"};

    private static final Pattern LIGAR = Pattern.compile(
        "^(?:(?:por favor|pode) )*(?:ligue|ligar|liga|inicie|iniciar|inicia|acorde|acordar|ative|ativar)\\s+"
        + "(?:o meu |a minha |o |a |meu |minha )?" + DISP + "\\b(.*)$");
    private static final Pattern STATUS = Pattern.compile(
        "\\b(?:status|situacao)\\b.*\\b" + DISP + "\\b|\\b" + DISP
        + "\\b.*\\b(?:esta ligado|esta ligada|esta online|esta acordado|ligou|ta ligado)\\b|\\b" + DISP + " ligado\\b");
    private static final Pattern ENERGIA = Pattern.compile(
        "^(?:(?:por favor|pode) )*(desligue|desligar|desliga|reinicie|reiniciar|bloqueie|bloquear)\\s+"
        + "(?:o meu |a minha |o |a |meu |minha )?" + DISP + "\\b.*$");
    private static final Pattern PARA_PC_FIM = Pattern.compile(
        "^(.*?)\\s+(?:no|do|ao|pro|para o|para|pra o|pra)\\s+(?:o |meu |minha )?" + DISP + "$");
    private static final Pattern PARA_PC_INICIO = Pattern.compile(
        "^(?:no|pro|para o|pra o)\\s+(?:meu )?" + DISP + "\\s+(.+)$");
    private static final Pattern ABRIR = Pattern.compile(
        "^(?:por favor )?(?:abra|abre|abrir|inicie|iniciar|execute|executar|jogar|jogue|joga|rode|rodar|carregue)\\s+(.+)$");

    private static boolean acha(String regex, String s) {
        return Pattern.compile(regex).matcher(s).find();
    }

    private static boolean ehPergunta(String n) {
        String[] t = n.split(" ");
        String ini = t.length >= 2 ? t[0] + " " + t[1] : t[0];
        for (String p : PERGUNTAS) {
            if (n.startsWith(p + " ") || n.equals(p) || ini.endsWith(" " + p)) return true;
        }
        return false;
    }

    static String extrairTermo(String n) {
        String t = n;
        for (String f : new String[]{"no youtube", "no google", "no bing", "no edge", "na internet", "pra mim",
            "para mim", "por favor"}) t = t.replace(f, " ");
        List<String> toks = new ArrayList<>(Arrays.asList(t.trim().split("\\s+")));
        for (int i = 0; i < toks.size(); i++) {
            if (toks.get(i).matches(VERBO_PESQUISA)) {
                toks = new ArrayList<>(toks.subList(i + 1, toks.size()));
                break;
            }
        }
        while (!toks.isEmpty() && FILLER.contains(toks.get(0))) toks.remove(0);
        return String.join(" ", toks).trim();
    }

    private static String limparPergunta(String n) {
        String t = n;
        for (String f : new String[]{"no youtube", "no google", "no bing", "no edge", "na internet", "por favor"})
            t = t.replace(f, " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Transforma a frase falada em uma acao. */
    static Acao interpretar(String bruto) {
        String n = removerAtivacao(normalizar(bruto == null ? "" : bruto));
        n = n.replaceFirst("^(?:eu )?(?:quero|vou|vamos|bora|preciso) (?=(?:abrir|jogar|iniciar))", "");
        if (n.isEmpty()) return new Acao(Tipo.NADA);
        String[] tk = n.split(" ");
        boolean curto = tk.length <= 8;
        String primeiros3 = juntar(tk, 0, 3);
        boolean verboBusca = acha("\\b" + VERBO_PESQUISA + "\\b", primeiros3);
        boolean pergunta = ehPergunta(n);

        // --- notebook ---
        Matcher m = LIGAR.matcher(n);
        if (m.matches()) {
            Acao a = new Acao(Tipo.LIGAR_NOTEBOOK);
            String extra = m.group(1).replaceFirst("^(?:\\s*(?:e depois|depois|e entao|entao|e)\\b\\s*)+", "").trim();
            extra = extra.replaceAll("^(?:pra mim|para mim|por favor)\\s*", "").trim();
            if (!extra.isEmpty()) {
                if (!extra.matches("^" + ACAO_INICIAL + "\\b.*")) {
                    extra = extra.split(" ").length <= 4 ? "abra " + extra : "";
                }
            }
            a.extra = extra;
            return a;
        }
        if (STATUS.matcher(n).find() && curto) return new Acao(Tipo.STATUS_NOTEBOOK);
        m = ENERGIA.matcher(n);
        if (m.matches()) {
            String v = m.group(1);
            String cmd = v.startsWith("deslig") ? "desligar o computador"
                : v.startsWith("reinic") ? "reiniciar o computador" : "bloquear o computador";
            return new Acao(Tipo.ENVIAR_PC).texto(cmd);
        }
        m = PARA_PC_FIM.matcher(n);
        if (m.matches() && m.group(1).matches("^" + ACAO_INICIAL + "\\b.*")) {
            return new Acao(Tipo.ENVIAR_PC).texto(m.group(1).trim());
        }
        m = PARA_PC_INICIO.matcher(n);
        if (m.matches()) return new Acao(Tipo.ENVIAR_PC).texto(m.group(1).trim());

        // --- respostas simples ---
        if (tk.length <= 7 && acha("\\bque horas\\b|\\bhoras sao\\b|\\bhora certa\\b", n)) return new Acao(Tipo.HORA);
        if (tk.length <= 7 && acha("\\bque dia (?:e )?hoje\\b|\\bdata de hoje\\b|\\bqual a data\\b|\\bdia da semana\\b", n))
            return new Acao(Tipo.DATA);
        if (curto && !verboBusca && acha("\\bpiada\\b", n)) return new Acao(Tipo.PIADA);
        if (curto && !verboBusca && acha("\\bo que voce pode fazer\\b|\\bo que voce sabe fazer\\b|"
            + "\\bquais sao os comandos\\b|\\bajuda\\b", n)) return new Acao(Tipo.AJUDA);

        String conta = calcular(n);
        if (conta != null) return new Acao(Tipo.CALCULO).texto(conta);

        // --- temporizador ---
        if (!verboBusca && acha("\\b(?:temporizador|timer|cronometro|alarme|despertador|me avise|me avisa|"
            + "me lembre|me lembra|me acorde)\\b", n)) {
            return new Acao(Tipo.TEMPORIZADOR).numero(parseDuracao(n)).texto(extrairMotivo(n));
        }

        // --- lanterna, volume ---
        if (curto && !verboBusca && acha("\\b(?:lanterna|flash)\\b", n)) {
            boolean desligar = acha("\\b(?:desligue|desligar|desliga|apague|apagar|desative|desativar)\\b", n);
            return new Acao(Tipo.LANTERNA).flag(!desligar);
        }
        if (curto && !verboBusca && !pergunta) {
            if (acha("\\b(?:silencie|silenciar|mutar|mute|desmutar|tire o mudo|tirar o mudo|modo mudo)\\b", n)) {
                return new Acao(Tipo.MUDO).flag(!acha("desmut|tire o mudo|tirar o mudo", n));
            }
            boolean comVolume = acha("\\bvolume\\b", n)
                || acha("\\b(?:aumente|aumenta|abaixe|abaixa|diminua|reduza|suba|baixe)\\b.*\\bsom\\b", n);
            if (comVolume) {
                boolean subir = acha("\\b(?:aumente|aumenta|suba|mais alto|mais forte)\\b", n);
                boolean descer = acha("\\b(?:abaixe|abaixa|diminua|reduza|baixe|mais baixo)\\b", n);
                Matcher num = Pattern.compile("\\b(\\d{1,3})\\b").matcher(numerosPorExtenso(n));
                if (num.find() && !subir && !descer) {
                    return new Acao(Tipo.VOLUME).texto("definir").numero(Math.min(100, Integer.parseInt(num.group(1))));
                }
                if (acha("\\bmaximo\\b", n)) return new Acao(Tipo.VOLUME).texto("definir").numero(100);
                if (acha("\\bminimo\\b", n)) return new Acao(Tipo.VOLUME).texto("definir").numero(0);
                if (subir) return new Acao(Tipo.VOLUME).texto("subir").numero(n.contains("muito") ? 4 : 2);
                if (descer) return new Acao(Tipo.VOLUME).texto("descer").numero(n.contains("muito") ? 4 : 2);
            }
        }

        // --- abrir aplicativo ---
        m = ABRIR.matcher(n);
        if (m.matches()) {
            List<String> toks = new ArrayList<>(Arrays.asList(m.group(1).split(" ")));
            while (!toks.isEmpty() && ABRIR_FILLER.contains(toks.get(0))) toks.remove(0);
            String nome = String.join(" ", toks).trim();
            if (!nome.isEmpty()) return new Acao(Tipo.ABRIR_APP).texto(nome);
        }

        // --- pesquisa ---
        if (acha("\\b" + VERBO_PESQUISA + "\\b", juntar(tk, 0, 8))) {
            return new Acao(Tipo.PESQUISA).texto(extrairTermo(n)).flag(n.contains("youtube"));
        }
        if (pergunta) return new Acao(Tipo.PESQUISA).texto(limparPergunta(n)).flag(n.contains("youtube"));
        if (tk.length >= 2) return new Acao(Tipo.PESQUISA).texto(limparPergunta(n));
        return new Acao(Tipo.NADA);
    }
}
