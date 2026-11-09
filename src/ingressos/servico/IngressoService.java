package ingressos.servico;

import ingressos.modelo.Evento;
import ingressos.modelo.EventoDados;
import ingressos.modelo.EventoNaoEncontradoException;
import ingressos.modelo.NegocioException;
import ingressos.modelo.Venda;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fachada thread-safe de todas as operações de venda. É o único ponto de entrada
 * usado pelo menu e pelas threads do simulador.
 *
 * <h2>Estratégia de locks (dois níveis)</h2>
 * <ol>
 *   <li><b>Lock do catálogo</b> ({@link ReentrantReadWriteLock}, justo): protege a ESTRUTURA do mapa
 *       de eventos. Buscar, listar, comprar e cancelar só precisam do lock de leitura (muitos em
 *       paralelo). Adicionar, remover e importar usam o lock de escrita (exclusivo).</li>
 *   <li><b>Lock de cada {@link Evento}</b> ({@code ReentrantLock}): protege o ESTOQUE daquele evento.
 *       Compras em eventos diferentes não se bloqueiam.</li>
 * </ol>
 *
 * <p><b>Ordem de aquisição fixa: catálogo -> evento</b>, nunca o contrário. Como ninguém pega o
 * lock de um evento e depois pede o do catálogo, não existe ciclo de espera e, portanto, não há deadlock.
 *
 * <p>Comprar segura o lock de leitura do catálogo durante a reserva de propósito: assim um
 * {@code removerEvento} (escrita) espera as compras em andamento terminarem, e nenhuma compra
 * acontece num evento que está sendo removido.
 */
public final class IngressoService {

    // 'true' = lock justo: escritores (adicionar/remover) não sofrem starvation com muitos leitores.
    private final ReentrantReadWriteLock catalogoLock = new ReentrantReadWriteLock(true);
    private final Map<Long, Evento> eventos = new TreeMap<>(); // guardado por catalogoLock
    private final AtomicLong proximoEventoId = new AtomicLong(1);
    private final AtomicLong proximaVendaId = new AtomicLong(1);

    // ---------------------------------------------------------------- escrita no catálogo

    public EventoDados adicionarEvento(String nome, String local, BigDecimal preco, int capacidade) {
        String n = limpar(nome);
        String l = limpar(local);
        if (n.isEmpty()) throw new NegocioException("O nome do evento é obrigatório.");
        if (l.isEmpty()) throw new NegocioException("O local do evento é obrigatório.");
        if (preco == null || preco.signum() < 0) throw new NegocioException("Preço inválido.");
        if (capacidade <= 0) throw new NegocioException("A capacidade deve ser maior que zero.");

        Lock w = catalogoLock.writeLock();
        w.lock();
        try {
            Evento evento = new Evento(proximoEventoId.getAndIncrement(), n, l, preco, capacidade);
            eventos.put(evento.id(), evento);
            return evento.snapshot();
        } finally {
            w.unlock();
        }
    }

    public EventoDados removerEvento(long id) {
        Lock w = catalogoLock.writeLock();
        w.lock();
        try {
            Evento removido = eventos.remove(id);
            if (removido == null) throw new EventoNaoEncontradoException(id);
            return removido.snapshot();
        } finally {
            w.unlock();
        }
    }

    // ---------------------------------------------------------------- leitura do catálogo

    public EventoDados buscarPorId(long id) {
        Lock r = catalogoLock.readLock();
        r.lock();
        try {
            return obter(id).snapshot();
        } finally {
            r.unlock();
        }
    }

    /** Busca por trecho do nome ou do local, sem diferenciar maiúsculas de minúsculas. */
    public List<EventoDados> buscarPorNome(String termo) {
        String t = limpar(termo).toLowerCase(Locale.ROOT);
        List<EventoDados> resultado = new ArrayList<>();
        Lock r = catalogoLock.readLock();
        r.lock();
        try {
            for (Evento e : eventos.values()) {
                // nome e local são imutáveis: filtra sem lock do evento e só tira snapshot dos que casam.
                if (e.nome().toLowerCase(Locale.ROOT).contains(t) || e.local().toLowerCase(Locale.ROOT).contains(t)) {
                    resultado.add(e.snapshot());
                }
            }
        } finally {
            r.unlock();
        }
        return resultado;
    }

    public List<EventoDados> listar() {
        return exportar();
    }

    // ---------------------------------------------------------------- vendas

    public Venda comprar(long eventoId, String cliente, int quantidade) {
        String c = limpar(cliente);
        if (c.isEmpty()) throw new NegocioException("O nome do cliente é obrigatório.");
        if (quantidade <= 0) throw new NegocioException("A quantidade deve ser maior que zero.");

        Lock r = catalogoLock.readLock();
        r.lock();
        try {
            // O id é gerado dentro do lock de leitura: importar() (escrita) não pode reiniciar o contador no meio.
            long vendaId = proximaVendaId.getAndIncrement();
            return obter(eventoId).reservar(vendaId, c, quantidade);
        } finally {
            r.unlock();
        }
    }

    public Venda cancelarCompra(long eventoId, long vendaId) {
        Lock r = catalogoLock.readLock();
        r.lock();
        try {
            return obter(eventoId).cancelar(vendaId);
        } finally {
            r.unlock();
        }
    }

    // ---------------------------------------------------------------- snapshot / restauração (persistência)

    /**
     * Foto de todo o catálogo para salvar em disco. Só segura o lock de leitura e o lock de cada evento
     * por alguns instantes (cópia em memória); a escrita em disco acontece FORA de qualquer lock,
     * então salvar não trava os compradores. Cada evento é copiado de forma consistente.
     */
    public List<EventoDados> exportar() {
        List<EventoDados> fotos = new ArrayList<>();
        Lock r = catalogoLock.readLock();
        r.lock();
        try {
            for (Evento e : eventos.values()) {
                fotos.add(e.snapshot());
            }
        } finally {
            r.unlock();
        }
        return fotos;
    }

    /** Substitui todo o catálogo pelos dados carregados do arquivo. */
    public void importar(List<EventoDados> dados) {
        Map<Long, Evento> novos = new TreeMap<>();
        long maxEvento = 0;
        long maxVenda = 0;
        for (EventoDados d : dados) { // montagem fora do lock: só o "swap" é exclusivo
            novos.put(d.id(), Evento.restaurar(d));
            maxEvento = Math.max(maxEvento, d.id());
            for (Venda v : d.vendas()) {
                maxVenda = Math.max(maxVenda, v.id());
            }
        }
        Lock w = catalogoLock.writeLock();
        w.lock();
        try {
            eventos.clear();
            eventos.putAll(novos);
            proximoEventoId.set(maxEvento + 1);
            proximaVendaId.set(maxVenda + 1);
        } finally {
            w.unlock();
        }
    }

    // ---------------------------------------------------------------- utilidades

    /** Chamar somente com o lock do catálogo (leitura ou escrita) adquirido. */
    private Evento obter(long id) {
        Evento e = eventos.get(id);
        if (e == null) throw new EventoNaoEncontradoException(id);
        return e;
    }

    /** Remove separadores do formato CSV e quebras de linha. */
    private static String limpar(String s) {
        return s == null ? "" : s.replaceAll("[;\\r\\n]", " ").trim();
    }
}
