package ingressos.akka.comandos;
import akka.actor.typed.ActorRef;
import ingressos.akka.Comando;
import ingressos.akka.Respostas;

public record Cancelar(long eventoId, long vendaId,
                       ActorRef<Respostas.VendaResp> replyTo) implements Comando {}