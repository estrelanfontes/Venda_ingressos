package ingressos.modelo;

/** Lançada quando o pedido excede a quantidade de ingressos ainda disponíveis. */
public class EstoqueInsuficienteException extends NegocioException {
    private static final long serialVersionUID = 1L;

    private final int disponiveis;

    public EstoqueInsuficienteException(long eventoId, int pedidos, int disponiveis) {
        super("Evento " + eventoId + ": pedido de " + pedidos + " ingresso(s), mas só há "
                + disponiveis + " disponível(is).");
        this.disponiveis = disponiveis;
    }

    public int disponiveis() {
        return disponiveis;
    }
}
