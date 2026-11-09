package ingressos.modelo;

public class EventoNaoEncontradoException extends NegocioException {
    private static final long serialVersionUID = 1L;

    public EventoNaoEncontradoException(long id) {
        super("Evento " + id + " não encontrado.");
    }
}
