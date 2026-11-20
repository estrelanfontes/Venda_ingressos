package ingressos.modelo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Um evento com estoque de ingressos.
 *
 * <p>Cada evento tem o seu próprio {@link ReentrantLock}. Todo acesso ao estado mutável
 * (contador de vendidos e mapa de vendas) acontece com esse lock adquirido, então compras
 * em eventos diferentes correm em paralelo e só compras no MESMO evento se serializam.
 */
public final class Evento {
    // Campos imutáveis: podem ser lidos por qualquer thread sem lock.
    private final long id;
    private final String nome;
    private final String local;
    private final BigDecimal preco;
    private final int capacidade;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Venda> vendas = new LinkedHashMap<>(); // guardado por lock
    private int vendidos;                                          // guardado por lock

    public Evento(long id, String nome, String local, BigDecimal preco, int capacidade) {
        this.id = id;
        this.nome = nome;
        this.local = local;
        this.preco = preco;
        this.capacidade = capacidade;
    }

    /** Reconstrói um evento a partir de um snapshot (usado ao carregar do arquivo). */
    public static Evento restaurar(EventoDados d) {
        Evento e = new Evento(d.id(), d.nome(), d.local(), d.preco(), d.capacidade());
        for (Venda v : d.vendas()) {
            e.vendas.put(v.id(), v);
            e.vendidos += v.quantidade();
        }
        if (e.vendidos > e.capacidade) {
            throw new NegocioException("Dados inconsistentes: evento " + d.id()
                    + " tem mais ingressos vendidos que a capacidade.");
        }
        return e;
    }

    public long id() {
        return id;
    }

    public String nome() {
        return nome;
    }

    public String local() {
        return local;
    }

    /**
     * Seção crítica: verificar o estoque e debitá-lo precisa ser UMA operação indivisível.
     * Sem o lock, duas threads poderiam ler "restam 1" e as duas vender (overselling).
     */
    public Venda reservar(long vendaId, String cliente, int quantidade) {
        lock.lock();
        try {
            int livres = capacidade - vendidos;
            if (quantidade > livres) {
                throw new EstoqueInsuficienteException(id, quantidade, livres);
            }
            Venda venda = new Venda(vendaId, id, cliente, quantidade,
                    preco.multiply(BigDecimal.valueOf(quantidade)), Instant.now());
            vendas.put(vendaId, venda);
            vendidos += quantidade;
            return venda;
        } finally {
            lock.unlock();
        }
    }

    /** Cancela uma venda e devolve os ingressos ao estoque. */
    public Venda cancelar(long vendaId) {
        lock.lock();
        try {
            Venda venda = vendas.remove(vendaId);
            if (venda == null) {
                throw new NegocioException("Venda " + vendaId + " não encontrada no evento " + id + ".");
            }
            vendidos -= venda.quantidade();
            return venda;
        } finally {
            lock.unlock();
        }
    }

    /** Foto consistente do evento (contador e vendas lidos sob o mesmo lock). */
    public EventoDados snapshot() {
        lock.lock();
        try {
            return new EventoDados(id, nome, local, preco, capacidade, vendidos, List.copyOf(vendas.values()));
        } finally {
            lock.unlock();
        }
    }
}
