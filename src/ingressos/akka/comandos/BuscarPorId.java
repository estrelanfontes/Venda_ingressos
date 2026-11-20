package ingressos.akka.comandos;
import akka.actor.typed.ActorRef;
import ingressos.akka.Comando;
import ingressos.akka.Respostas;

public record BuscarPorId(long id, ActorRef<Respostas.EventoOpcionalResp> replyTo) implements Comando {}