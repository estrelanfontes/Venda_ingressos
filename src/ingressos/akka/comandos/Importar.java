package ingressos.akka.comandos;
import akka.actor.typed.ActorRef;
import ingressos.akka.Comando;
import ingressos.akka.Respostas;
import ingressos.modelo.EventoDados;
import java.util.List;

public record Importar(List<EventoDados> dados, ActorRef<Respostas.OkResp> replyTo) implements Comando {}