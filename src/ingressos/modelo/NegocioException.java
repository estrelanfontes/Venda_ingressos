package ingressos.modelo;

/** Erro de regra de negócio (entrada inválida, evento inexistente, estoque esgotado...). */
public class NegocioException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NegocioException(String mensagem) {
        super(mensagem);
    }
}
