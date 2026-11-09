package ingressos.persistencia;

import ingressos.modelo.EventoDados;
import ingressos.modelo.Venda;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistência em um único arquivo de texto:
 * <pre>
 * #ingressos-v1
 * E;id;nome;local;preco;capacidade
 * V;id;eventoId;cliente;quantidade;total;dataIso
 * </pre>
 *
 * <p>Salvar escreve num arquivo temporário e depois o move por cima do definitivo (move atômico),
 * então uma queda no meio do salvamento nunca deixa o arquivo pela metade. O {@code lock} serializa
 * salvamentos/carregamentos concorrentes sobre o mesmo arquivo. Esta classe não conhece o serviço
 * nem seus locks, e por isso nenhum lock do domínio é segurado durante o I/O.
 */
public final class ArmazenamentoCsv {
    private static final String CABECALHO = "#ingressos-v1";

    private final Path arquivo;
    private final ReentrantLock lock = new ReentrantLock();

    public ArmazenamentoCsv(Path arquivo) {
        this.arquivo = arquivo;
    }

    public Path arquivo() {
        return arquivo;
    }

    public boolean existe() {
        return Files.exists(arquivo);
    }

    public void salvar(List<EventoDados> eventos) throws IOException {
        lock.lock();
        try {
            Path pasta = arquivo.toAbsolutePath().getParent();
            Files.createDirectories(pasta);
            Path tmp = arquivo.toAbsolutePath().resolveSibling(arquivo.getFileName() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(CABECALHO);
                w.newLine();
                for (EventoDados e : eventos) {
                    w.write(String.join(";", "E", String.valueOf(e.id()), e.nome(), e.local(),
                            e.preco().toPlainString(), String.valueOf(e.capacidade())));
                    w.newLine();
                    for (Venda v : e.vendas()) {
                        w.write(String.join(";", "V", String.valueOf(v.id()), String.valueOf(v.eventoId()),
                                v.cliente(), String.valueOf(v.quantidade()), v.total().toPlainString(),
                                v.data().toString()));
                        w.newLine();
                    }
                }
            }
            mover(tmp, arquivo.toAbsolutePath());
        } finally {
            lock.unlock();
        }
    }

    /** Lê o arquivo; devolve lista vazia se ele ainda não existe. */
    public List<EventoDados> carregar() throws IOException {
        lock.lock();
        try {
            if (!Files.exists(arquivo)) {
                return List.of();
            }
            Map<Long, Parcial> eventos = new LinkedHashMap<>();
            try (BufferedReader r = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
                String linha;
                int n = 0;
                while ((linha = r.readLine()) != null) {
                    n++;
                    if (linha.isBlank() || linha.startsWith("#")) continue;
                    try {
                        interpretar(linha, eventos);
                    } catch (RuntimeException ex) {
                        throw new IOException("Arquivo inválido na linha " + n + ": " + ex.getMessage(), ex);
                    }
                }
            }
            List<EventoDados> resultado = new ArrayList<>();
            for (Parcial p : eventos.values()) {
                int vendidos = p.vendas.stream().mapToInt(Venda::quantidade).sum();
                resultado.add(new EventoDados(p.id, p.nome, p.local, p.preco, p.capacidade, vendidos, p.vendas));
            }
            return resultado;
        } finally {
            lock.unlock();
        }
    }

    private static void interpretar(String linha, Map<Long, Parcial> eventos) {
        String[] c = linha.split(";", -1);
        switch (c[0]) {
            case "E" -> {
                if (c.length != 6) throw new IllegalArgumentException("evento com " + c.length + " campos");
                Parcial p = new Parcial(Long.parseLong(c[1]), c[2], c[3], new BigDecimal(c[4]), Integer.parseInt(c[5]));
                eventos.put(p.id, p);
            }
            case "V" -> {
                if (c.length != 7) throw new IllegalArgumentException("venda com " + c.length + " campos");
                long eventoId = Long.parseLong(c[2]);
                Parcial p = eventos.get(eventoId);
                if (p == null) throw new IllegalArgumentException("venda de evento inexistente: " + eventoId);
                p.vendas.add(new Venda(Long.parseLong(c[1]), eventoId, c[3], Integer.parseInt(c[4]),
                        new BigDecimal(c[5]), Instant.parse(c[6])));
            }
            default -> throw new IllegalArgumentException("tipo de registro desconhecido: " + c[0]);
        }
    }

    private static void mover(Path origem, Path destino) throws IOException {
        try {
            Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class Parcial {
        final long id;
        final String nome;
        final String local;
        final BigDecimal preco;
        final int capacidade;
        final List<Venda> vendas = new ArrayList<>();

        Parcial(long id, String nome, String local, BigDecimal preco, int capacidade) {
            this.id = id;
            this.nome = nome;
            this.local = local;
            this.preco = preco;
            this.capacidade = capacidade;
        }
    }
}
