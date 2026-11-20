package ingressos;

import ingressos.modelo.EventoDados;
import ingressos.modelo.NegocioException;
import ingressos.modelo.Venda;
import ingressos.persistencia.ArmazenamentoCsv;
import ingressos.servico.IngressoService;
import ingressos.simulacao.Simulador;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Scanner;
import ingressos.servico.Repositorio;
import ingressos.servico.RepositorioAkka;
import java.nio.file.Path;
import java.util.Arrays;
/**
 * Menu de console. Uso:
 * <pre>
 *   java -cp out ingressos.Main                      (menu interativo)
 *   java -cp out ingressos.Main --simular [N] [ops]  (simulação direta, sem menu)
 * </pre>
 */
public final class Main {
    private static final NumberFormat MOEDA = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));

    private final Repositorio repositorio;
    private final ArmazenamentoCsv armazenamento;
    private final Scanner in = new Scanner(System.in, StandardCharsets.UTF_8);

    public Main(Repositorio repositorio, ArmazenamentoCsv armazenamento) {
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
    }

    public static void main(String[] args) throws Exception {
        ArmazenamentoCsv csv = new ArmazenamentoCsv(Path.of("dados", "ingressos.csv"));
        boolean usarAkka = Arrays.asList(args).contains("--akka");
        Repositorio repositorio = usarAkka
                ? new RepositorioAkka(csv)
                : new IngressoService(csv);

        if (args.length > 0 && args[0].equals("--gui")) {
            javax.swing.SwingUtilities.invokeLater(() ->
                new ingressos.ui.JanelaPrincipal(repositorio, csv).setVisible(true));
            return;
        }
        if (args.length > 0 && args[0].equals("--simular")) {
            int usuarios = args.length > 1 ? Integer.parseInt(args[1]) : 20;
            int operacoes = args.length > 2 ? Integer.parseInt(args[2]) : 200;
            Simulador.Resultado r = new Simulador().executar(repositorio, usuarios, operacoes, 42L);
            Simulador.imprimir(r, System.out);
            if (repositorio instanceof RepositorioAkka akka) akka.encerrar();
            System.exit(r.ok() ? 0 : 1);
        }
        try {
            new Main(repositorio, csv).executar();
        } finally {
            if (repositorio instanceof RepositorioAkka akka) akka.encerrar();
        }
    }

    private void executar() {
        carregarSeExistir();
        try {
            while (true) {
                imprimirMenu();
                String opcao = in.nextLine().trim();
                try {
                    switch (opcao) {
                        case "1" -> listar();
                        case "2" -> adicionar();
                        case "3" -> remover();
                        case "4" -> buscar();
                        case "5" -> comprar();
                        case "6" -> cancelar();
                        case "7" -> salvar();
                        case "8" -> carregar();
                        case "9" -> simular();
                        case "0" -> {
                            if (perguntar("Salvar antes de sair? (s/n): ").equalsIgnoreCase("s")) {
                                salvar();
                            }
                            System.out.println("Até logo!");
                            return;
                        }
                        default -> System.out.println("Opção inválida.");
                    }
                } catch (NegocioException e) {
                    System.out.println("Erro: " + e.getMessage());
                } catch (IOException e) {
                    System.out.println("Erro de arquivo: " + e.getMessage());
                } catch (NumberFormatException e) {
                    System.out.println("Erro: valor numérico inválido.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.out.println("Erro inesperado: " + e);
                }
            }
        } catch (NoSuchElementException fimDaEntrada) {
            System.out.println("\nEntrada encerrada.");
        }
    }

    private void imprimirMenu() {
        System.out.println("""

                ===== VENDA DE INGRESSOS =====
                1) Listar eventos
                2) Adicionar evento
                3) Remover evento
                4) Buscar evento (nome ou local)
                5) Comprar ingressos
                6) Cancelar compra
                7) Salvar em arquivo
                8) Carregar do arquivo
                9) Simular N usuários simultâneos
                0) Sair""");
        System.out.print("Escolha: ");
    }

    // ------------------------------------------------------------------ opções

    private void listar() {
        imprimirTabela(repositorio.listar());
    }

    private void adicionar() {
        String nome = perguntar("Nome do evento: ");
        String local = perguntar("Local: ");
        BigDecimal preco = lerPreco(perguntar("Preço do ingresso (ex.: 150,00): "));
        int capacidade = Integer.parseInt(perguntar("Capacidade (nº de ingressos): "));
        EventoDados e = repositorio.adicionarEvento(nome, local, preco, capacidade);
        System.out.println("Evento criado com id " + e.id() + ".");
    }

    private void remover() {
        long id = Long.parseLong(perguntar("Id do evento a remover: "));
        EventoDados e = repositorio.removerEvento(id);
        System.out.println("Removido: " + e.nome() + " (" + e.vendas().size() + " venda(s) descartada(s)).");
    }

    private void buscar() {
        imprimirTabela(repositorio.buscarPorNome(perguntar("Termo de busca: ")));
    }

    private void comprar() {
        long eventoId = Long.parseLong(perguntar("Id do evento: "));
        String cliente = perguntar("Nome do cliente: ");
        int qtd = Integer.parseInt(perguntar("Quantidade: "));
        Venda v = repositorio.comprar(eventoId, cliente, qtd);
        System.out.printf("Compra %d confirmada: %d ingresso(s), total %s.%n", v.id(), v.quantidade(), MOEDA.format(v.total()));
    }

    private void cancelar() {
        long eventoId = Long.parseLong(perguntar("Id do evento: "));
        long vendaId = Long.parseLong(perguntar("Código da compra: "));
        Venda v = repositorio.cancelarCompra(eventoId, vendaId);
        System.out.printf("Compra %d cancelada (%d ingresso(s) devolvido(s)).%n", v.id(), v.quantidade());
    }

    private void salvar() {
        repositorio.salvar();
        System.out.println("Salvo em " + armazenamento.arquivo() + ".");
    }

    private void carregar() throws IOException {
        if (!armazenamento.existe()) {
            System.out.println("Ainda não existe arquivo salvo.");
            return;
        }
        repositorio.importar(armazenamento.carregar());
        System.out.println("Dados carregados de " + armazenamento.arquivo() + ".");
    }

    private void carregarSeExistir() {
        try {
            if (armazenamento.existe()) {
                repositorio.importar(armazenamento.carregar());
                System.out.println("Dados anteriores carregados de " + armazenamento.arquivo() + ".");
            }
        } catch (IOException | NegocioException e) {
            System.out.println("Não foi possível carregar os dados anteriores: " + e.getMessage());
        }
    }

    private void simular() throws Exception {
        int usuarios = Integer.parseInt(perguntar("Número de usuários simultâneos (N): "));
        int ops = Integer.parseInt(perguntar("Operações por usuário: "));
        if (usuarios <= 0 || ops <= 0) {
            throw new NegocioException("N e operações devem ser maiores que zero.");
        }
        System.out.println("Simulando (usa um catálogo isolado; seus dados não são alterados)...");
    }

    // ------------------------------------------------------------------ utilidades de UI

    private void imprimirTabela(List<EventoDados> eventos) {
        if (eventos.isEmpty()) {
            System.out.println("Nenhum evento encontrado.");
            return;
        }
        System.out.printf("%-4s %-28s %-20s %12s %12s%n", "ID", "EVENTO", "LOCAL", "PREÇO", "DISPONÍVEIS");
        for (EventoDados e : eventos) {
            System.out.printf("%-4d %-28s %-20s %12s %6d/%-5d%n", e.id(), cortar(e.nome(), 28), cortar(e.local(), 20),
                    MOEDA.format(e.preco()), e.disponiveis(), e.capacidade());
        }
    }

    private static String cortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static BigDecimal lerPreco(String texto) {
        return new BigDecimal(texto.trim().replace(',', '.')).setScale(2, RoundingMode.HALF_UP);
    }

    private String perguntar(String mensagem) {
        System.out.print(mensagem);
        return in.nextLine();
    }
}
