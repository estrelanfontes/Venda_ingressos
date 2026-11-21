package ingressos.simulacao;
import ingressos.servico.Repositorio;
import ingressos.modelo.EstoqueInsuficienteException;
import ingressos.modelo.EventoDados;
import ingressos.modelo.Venda;
import ingressos.persistencia.ArmazenamentoCsv;
import ingressos.servico.IngressoService;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collection;


/**
 * Simula N usuários simultâneos (uma thread por usuário) usando um {@link IngressoService} isolado
 * (os dados reais do menu não são tocados) e, ao final, confere as invariantes do sistema:
 * <ul>
 *   <li>nenhum evento vendeu mais que a capacidade (sem overselling);</li>
 *   <li>o contador de vendidos de cada evento bate com a soma das suas vendas;</li>
 *   <li>ingressos vendidos = comprados - cancelados (nada se perdeu nem foi duplicado);</li>
 *   <li>ids de venda únicos;</li>
 *   <li>uma thread "salvadora" grava o arquivo continuamente DURANTE a simulação e cada foto
 *       gravada também precisa estar consistente.</li>
 * </ul>
 */
public final class Simulador {

    public record Resultado(int usuarios, int operacoesPorUsuario, long buscas, long listagens, long compras,
                            long ingressosComprados, long semEstoque, long cancelamentos, long ingressosCancelados,
                            long eventosCriados, int salvamentos, long ingressosVendidosFinal, long duracaoMs,
                            List<String> violacoes, List<Long> latBuscas, List<Long> latCompras,
                        List<Long> latCancelamentos, List<Long> latListagens) {
        public boolean ok() {
            return violacoes.isEmpty();
        }
    }

    /** Latências (em nanossegundos) por tipo de operação, coletadas por thread, sem lock. */
    private static final class Amostras {
        final List<Long> buscas        = new ArrayList<>();
        final List<Long> compras       = new ArrayList<>();
        final List<Long> cancelamentos = new ArrayList<>();
        final List<Long> listagens     = new ArrayList<>();
    }

    /** Par (contadores + amostras) devolvido por cada thread. */
    private record ResultadoThread(Contadores contadores, Amostras amostras) {}

    /** Contadores locais de cada thread (sem compartilhamento, logo sem lock); somados no fim. */
    private static final class Contadores {
        long buscas, listagens, compras, ingComprados, semEstoque, cancelamentos, ingCancelados, eventosCriados;
    }

    public Resultado executar(Repositorio service, int usuarios, int ops, long seed) throws Exception {
        return executar(service, usuarios, Math.max(50, usuarios * ops / 10), ops, seed);
    }

    public Resultado executar(Repositorio service, int usuarios, int capacidade, int opsPorUsuario, long seed) throws Exception {
        List<Long> ids = new CopyOnWriteArrayList<>();
        // REMOVIDO: capacidade = Math.max(50, usuarios * opsPorUsuario / 10);  (usa o parâmetro)
        for (int i = 1; i <= 5; i++) {
            ids.add(service.adicionarEvento("Evento Simulado " + i, "Arena " + i,
                    BigDecimal.valueOf(50L * i), capacidade).id());
        }

        Queue<String> violacoes = new ConcurrentLinkedQueue<>();
        Path pasta = Files.createTempDirectory("sim-ingressos");
        ArmazenamentoCsv armazenamento = new ArmazenamentoCsv(pasta.resolve("ingressos.csv"));

        // Thread que salva em paralelo com as compras (prova que salvar não quebra a consistência).
        AtomicBoolean rodando = new AtomicBoolean(true);
        AtomicInteger salvamentos = new AtomicInteger();
        Thread salvador = new Thread(() -> {
            try {
                while (rodando.get()) {
                    List<EventoDados> foto = service.exportar();
                    conferir(foto, ids, "foto #" + salvamentos.get(), violacoes);
                    armazenamento.salvar(foto);
                    salvamentos.incrementAndGet();
                    Thread.sleep(10);
                }
            } catch (InterruptedException ignorada) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                violacoes.add("Falha na thread salvadora: " + e);
            }
        }, "salvador");

        ExecutorService pool = Executors.newFixedThreadPool(usuarios);
        CountDownLatch prontos = new CountDownLatch(usuarios);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<ResultadoThread>> futuros = new ArrayList<>();
        long inicio = System.nanoTime();

        try {
            for (int u = 1; u <= usuarios; u++) {
                final int uid = u;
            Callable<ResultadoThread> usuario = () -> {
                Random rnd = new Random(seed + uid);
                Contadores c = new Contadores();
                Amostras amostras = new Amostras();
                Deque<Venda> minhasCompras = new ArrayDeque<>();
                prontos.countDown();
                largada.await();
                for (int i = 0; i < opsPorUsuario; i++) {
                    executarOperacao(rnd, service, ids, minhasCompras, c, uid, i, amostras);
                }
                return new ResultadoThread(c, amostras);
            };
                futuros.add(pool.submit(usuario));
            }

            prontos.await();
            salvador.start();
            largada.countDown();

            Contadores total = new Contadores();
            Amostras totalAmostras = new Amostras();
            for (Future<ResultadoThread> f : futuros) {
                ResultadoThread r = f.get(); // propaga qualquer exceção inesperada de uma thread de usuário
                Contadores c = r.contadores();
                total.buscas += c.buscas;
                total.listagens += c.listagens;
                total.compras += c.compras;
                total.ingComprados += c.ingComprados;
                total.semEstoque += c.semEstoque;
                total.cancelamentos += c.cancelamentos;
                total.ingCancelados += c.ingCancelados;
                total.eventosCriados += c.eventosCriados;

            totalAmostras.buscas.addAll(r.amostras().buscas);
            totalAmostras.compras.addAll(r.amostras().compras);
            totalAmostras.cancelamentos.addAll(r.amostras().cancelamentos);
            totalAmostras.listagens.addAll(r.amostras().listagens);
            }
            long duracaoMs = (System.nanoTime() - inicio) / 1_000_000;

            rodando.set(false);
            salvador.join();

            // ---- conferência final
            List<EventoDados> finalFoto = service.exportar();
            conferir(finalFoto, ids, "estado final", violacoes);   
            long vendidos = finalFoto.stream().filter(e -> ids.contains(e.id())).mapToLong(EventoDados::vendidos).sum();
            long esperado = total.ingComprados - total.ingCancelados;
            if (vendidos != esperado) {
                violacoes.add("Conservação violada: vendidos=" + vendidos + " mas comprados-cancelados=" + esperado);
            }

            armazenamento.salvar(finalFoto);
            List<EventoDados> relido = armazenamento.carregar();
            long vendidosRelido = relido.stream()
                    .filter(e -> ids.contains(e.id()))                 
                    .mapToLong(EventoDados::vendidos)
                    .sum();
            long eventosRelido = relido.stream().filter(e -> ids.contains(e.id())).count();
            if (eventosRelido != ids.size() || vendidosRelido != vendidos) {   
                violacoes.add("Arquivo salvo não bate com o estado em memória (" + vendidosRelido + " x " + vendidos + ").");
            }

            return new Resultado(usuarios, opsPorUsuario, total.buscas, total.listagens, total.compras,
                    total.ingComprados, total.semEstoque, total.cancelamentos, total.ingCancelados,
                    total.eventosCriados, salvamentos.get(), vendidos, duracaoMs, new ArrayList<>(violacoes), totalAmostras.buscas,
                    totalAmostras.compras, totalAmostras.cancelamentos, totalAmostras.listagens);
        } finally {
            rodando.set(false);
            pool.shutdownNow();
            salvador.interrupt();
            apagar(pasta);
        }
    }

        /** Percentil (0..100) em milissegundos. Lista vazia → 0. */
    private static double percentil(List<Long> nanos, double p) {
        if (nanos.isEmpty()) return 0.0;
        List<Long> ordenado = new ArrayList<>(nanos);
        ordenado.sort(null);
        int idx = (int) Math.ceil(p / 100.0 * ordenado.size()) - 1;
        idx = Math.max(0, Math.min(ordenado.size() - 1, idx));
        return ordenado.get(idx) / 1_000_000.0;
    }

    /** Imprime linha de latência (p50/p95/p99 + min/max + amostras). */
    private static void imprimirLatencia(PrintStream out, String rotulo, List<Long> amostras) {
        if (amostras.isEmpty()) {
            out.printf("Latência %-13s: (sem amostras)%n", rotulo);
            return;
        }
        List<Long> ordenado = new ArrayList<>(amostras);
        ordenado.sort(null);
        double min = ordenado.get(0) / 1_000_000.0;
        double max = ordenado.get(ordenado.size() - 1) / 1_000_000.0;
        out.printf("Latência %-13s: n=%d  p50=%.3f  p95=%.3f  p99=%.3f  min=%.3f  max=%.3f ms%n",
                rotulo, amostras.size(),
                percentil(amostras, 50), percentil(amostras, 95), percentil(amostras, 99),
                min, max);
    }

    private static void conferir(List<EventoDados> foto, Collection<Long> idsSimulacao, String rotulo, Queue<String> violacoes) {
        Set<Long> idsVenda = new HashSet<>();
        for (EventoDados d : foto) {
            if (!idsSimulacao.contains(d.id())) continue;   // <-- ignora eventos antigos
            if (d.vendidos() < 0 || d.vendidos() > d.capacidade()) {
                violacoes.add(rotulo + ": evento " + d.id() + " com vendidos=" + d.vendidos()
                        + " fora de [0," + d.capacidade() + "]");
            }
            if (d.vendidos() != d.somaVendas()) {
                violacoes.add(rotulo + ": evento " + d.id() + " contador=" + d.vendidos()
                        + " mas soma das vendas=" + d.somaVendas());
            }
            for (Venda v : d.vendas()) {
                if (!idsVenda.add(v.id())) {
                    violacoes.add(rotulo + ": id de venda duplicado " + v.id());
                }
            }
        }
    }

    private static void executarOperacao(Random rnd, Repositorio service, List<Long> ids,
                                        Deque<Venda> minhasCompras, Contadores c,
                                        int uid, int i, Amostras amostras) {
        int sorteio = rnd.nextInt(100);
        if (sorteio < 30) {
            long t0 = System.nanoTime();
            service.buscarPorNome("Evento Simulado " + (1 + rnd.nextInt(5)));
            amostras.buscas.add(System.nanoTime() - t0);
            c.buscas++;
        } else if (sorteio < 78) {
            long eventoId = ids.get(rnd.nextInt(ids.size()));
            int qtd = 1 + rnd.nextInt(4);
            long t0 = System.nanoTime();
            try {
                minhasCompras.addLast(service.comprar(eventoId, "usuario-" + uid, qtd));
                c.compras++;
                c.ingComprados += qtd;
            } catch (EstoqueInsuficienteException esgotado) {
                c.semEstoque++;
            } finally {
                amostras.compras.add(System.nanoTime() - t0);
            }
        } else if (sorteio < 93) {
            Venda v = minhasCompras.pollFirst();
            if (v != null) {
                long t0 = System.nanoTime();
                service.cancelarCompra(v.eventoId(), v.id());
                amostras.cancelamentos.add(System.nanoTime() - t0);
                c.cancelamentos++;
                c.ingCancelados += v.quantidade();
            }
        } else if (sorteio < 98) {
            long t0 = System.nanoTime();
            service.listar();
            amostras.listagens.add(System.nanoTime() - t0);
            c.listagens++;
        } else {
            ids.add(service.adicionarEvento("Evento Extra " + uid + "-" + rnd.nextInt(1000),
                    "Teatro", BigDecimal.TEN, 30).id());
            c.eventosCriados++;
        }
    }

    private static void apagar(Path pasta) {
        try (var arquivos = Files.walk(pasta)) {
            arquivos.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignorada) {
            // limpeza best-effort
        }
    }

    public static void imprimir(Resultado r, PrintStream out) {
        out.println("---------------- Resultado da simulação ----------------");
        out.printf("Usuários simultâneos : %d (%d operações cada)%n", r.usuarios(), r.operacoesPorUsuario());
        out.printf("Duração              : %d ms%n", r.duracaoMs());
        out.printf("Buscas / listagens   : %d / %d%n", r.buscas(), r.listagens());
        out.printf("Compras aprovadas    : %d (%d ingressos)%n", r.compras(), r.ingressosComprados());
        out.printf("Compras sem estoque  : %d%n", r.semEstoque());
        out.printf("Cancelamentos        : %d (%d ingressos)%n", r.cancelamentos(), r.ingressosCancelados());
        out.printf("Eventos criados      : %d%n", r.eventosCriados());
        out.printf("Salvamentos paralelos: %d%n", r.salvamentos());
        out.printf("Ingressos vendidos   : %d (esperado: %d)%n", r.ingressosVendidosFinal(),
                r.ingressosComprados() - r.ingressosCancelados());

                
        if (r.ok()) {
            out.println("Invariantes          : OK (sem overselling, contadores consistentes, arquivo íntegro)");
        } else {
            out.println("Invariantes          : VIOLADAS");
            r.violacoes().stream().limit(10).forEach(v -> out.println("  - " + v));
        }

        out.println("---------------- Latência por operação (ms) ----------------");
        imprimirLatencia(out, "buscas",        r.latBuscas());
        imprimirLatencia(out, "compras",       r.latCompras());
        imprimirLatencia(out, "cancelamentos", r.latCancelamentos());
        imprimirLatencia(out, "listagens",     r.latListagens());
    }
}