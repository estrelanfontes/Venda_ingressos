package ingressos.modelo;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cópia imutável (snapshot) do estado de um {@link Evento} num instante.
 * É isso que sai do serviço para a UI, para a persistência e para o simulador:
 * ninguém fora do pacote enxerga o objeto mutável protegido por lock.
 */
public record EventoDados(long id, String nome, String local, BigDecimal preco,
                          int capacidade, int vendidos, List<Venda> vendas) {

    public int disponiveis() {
        return capacidade - vendidos;
    }

    /** Soma recalculada a partir das vendas; deve ser sempre igual a {@link #vendidos()}. */
    public int somaVendas() {
        return vendas.stream().mapToInt(Venda::quantidade).sum();
    }
}
