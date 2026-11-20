package ingressos.servico;

import ingressos.modelo.EventoDados;
import ingressos.modelo.Venda;
import java.math.BigDecimal;
import java.util.List;

public interface Repositorio {
    EventoDados adicionarEvento(String nome, String local, BigDecimal preco, int capacidade);
    EventoDados removerEvento(long id);
    EventoDados buscarPorId(long id);
    List<EventoDados> buscarPorNome(String termo);
    List<EventoDados> listar();
    Venda comprar(long eventoId, String cliente, int quantidade);
    Venda cancelarCompra(long eventoId, long vendaId);
    List<EventoDados> exportar();
    void importar(List<EventoDados> dados);
    void salvar();
}