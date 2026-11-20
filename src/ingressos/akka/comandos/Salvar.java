package ingressos.akka.comandos;
import akka.actor.typed.ActorRef;
import ingressos.akka.Comando;
import ingressos.akka.Respostas;

public record Salvar(ActorRef<Respostas.OkResp> replyTo) implements Comando {}