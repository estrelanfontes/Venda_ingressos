package ingressos.ui;
import ingressos.servico.Repositorio;
import ingressos.modelo.EventoDados;
import ingressos.modelo.NegocioException;
import ingressos.modelo.Venda;
import ingressos.persistencia.ArmazenamentoCsv;
import ingressos.servico.IngressoService;
import ingressos.simulacao.Simulador;
import java.awt.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;


@SuppressWarnings("serial")
public final class JanelaPrincipal extends JFrame {

    private static final NumberFormat MOEDA = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));

    private final Repositorio repositorio;
    private final ArmazenamentoCsv armazenamento;

    private final ModeloTabela modelo = new ModeloTabela();
    private final JTable tabela = new JTable(modelo);
    private final JTextArea log = new JTextArea(8, 60);

    // Campos de compra
    private final JTextField campoEventoId = new JTextField(6);
    private final JTextField campoCliente = new JTextField(15);
    private final JTextField campoQtd = new JTextField(4);

    // Campos de criação
    private final JTextField campoNome = new JTextField(15);
    private final JTextField campoLocal = new JTextField(15);
    private final JTextField campoPreco = new JTextField(8);
    private final JTextField campoCapacidade = new JTextField(6);

    private final JTextField campoBusca = new JTextField(20);

    // Simulação em background
    private final AtomicBoolean simulando = new AtomicBoolean(false);

    private String termoBuscaAtivo = ""; 

    public JanelaPrincipal(Repositorio repositorio, ArmazenamentoCsv armazenamento) {
        super("Venda de Ingressos");
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 750);
        setLocationRelativeTo(null);
        construirUI();
        carregarSeExistir();
        iniciarAtualizacaoAutomatica();
    }

    // ------------------------------------------------------------- construção da UI

    private void construirUI() {
        JTabbedPane abas = new JTabbedPane();
        abas.addTab("Eventos", abaEventos());
        abas.addTab("Comprar / Cancelar", abaCompras());
        abas.addTab("Simulação", abaSimulacao());
        abas.addTab("Log", new JScrollPane(log));
        add(abas, BorderLayout.CENTER);

        // Barra de status
        JLabel status = new JLabel(" Pronto.");
        add(status, BorderLayout.SOUTH);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    }

    private JPanel abaEventos() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        tabela.setAutoCreateRowSorter(true);
        tabela.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabela.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && tabela.getSelectedRow() >= 0) {
                EventoDados d = modelo.eventoNaLinha(tabela.convertRowIndexToModel(tabela.getSelectedRow()));
                if (d != null) campoEventoId.setText(String.valueOf(d.id()));
            }
        });
        p.add(new JScrollPane(tabela), BorderLayout.CENTER);

        // Barra superior: busca + ações
        JPanel topo = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topo.add(new JLabel("Buscar:"));
        topo.add(campoBusca);

        campoBusca.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { seVazioMostrarTodos(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { seVazioMostrarTodos(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { seVazioMostrarTodos(); }

        private void seVazioMostrarTodos() {
            if (campoBusca.getText().trim().isEmpty()) {
                termoBuscaAtivo = "";
                atualizarTabela();   // volta a listar todos
            }
        }
    });

        JButton bBuscar = new JButton("Buscar");
        bBuscar.addActionListener(e -> buscar());
        topo.add(bBuscar);
        JButton bTodos = new JButton("Mostrar todos");
        bTodos.addActionListener(e -> {
            campoBusca.setText("");
            termoBuscaAtivo = "";
            atualizarTabela();
        });

        JButton bSalvar = new JButton("Salvar");
        bSalvar.addActionListener(e -> salvar());
        topo.add(bSalvar);
        JButton bCarregar = new JButton("Carregar");
        bCarregar.addActionListener(e -> carregar());
        topo.add(bCarregar);

        p.add(topo, BorderLayout.NORTH);

        // Rodapé: formulário de criação + remover
        JPanel rodape = new JPanel(new FlowLayout(FlowLayout.LEFT));
        rodape.add(new JLabel("Novo evento —"));
        rodape.add(new JLabel("Nome:"));
        rodape.add(campoNome);
        rodape.add(new JLabel("Local:"));
        rodape.add(campoLocal);
        rodape.add(new JLabel("Preço:"));
        rodape.add(campoPreco);
        rodape.add(new JLabel("Capacidade:"));
        rodape.add(campoCapacidade);
        JButton bCriar = new JButton("Criar");
        bCriar.addActionListener(e -> criarEvento());
        rodape.add(bCriar);
        JButton bRemover = new JButton("Remover selecionado");
        bRemover.addActionListener(e -> removerEvento());
        rodape.add(bRemover);

        p.add(rodape, BorderLayout.SOUTH);
        return p;
    }

    private JPanel abaCompras() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        int y = 0;

        g.gridx = 0; g.gridy = y; p.add(new JLabel("Id do evento:"), g);
        g.gridx = 1; p.add(campoEventoId, g);
        y++;

        g.gridx = 0; g.gridy = y; p.add(new JLabel("Cliente:"), g);
        g.gridx = 1; p.add(campoCliente, g);
        y++;

        g.gridx = 0; g.gridy = y; p.add(new JLabel("Quantidade:"), g);
        g.gridx = 1; p.add(campoQtd, g);
        y++;

        JButton bComprar = new JButton("Comprar");
        bComprar.addActionListener(e -> comprar());
        g.gridx = 0; g.gridy = y; p.add(bComprar, g);

        JButton bCancelar = new JButton("Cancelar última venda do cliente");
        bCancelar.addActionListener(e -> cancelarUltima());
        g.gridx = 1; p.add(bCancelar, g);

        return p;
    }

    private JPanel abaSimulacao() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;

        JTextField u = new JTextField("20", 6);
        JTextField ops = new JTextField("200", 6);

        g.gridx = 0; g.gridy = 0; p.add(new JLabel("Usuários simultâneos:"), g);
        g.gridx = 1; p.add(u, g);
        g.gridx = 0; g.gridy = 1; p.add(new JLabel("Operações por usuário:"), g);
        g.gridx = 1; p.add(ops, g);

        JButton b = new JButton("Executar simulação (isolada)");
        JTextArea saida = new JTextArea(20, 70);
        saida.setEditable(false);
        saida.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        b.addActionListener(e -> {
            if (!simulando.compareAndSet(false, true)) return;
            b.setEnabled(false);
            saida.setText("Executando...");
            int n = Integer.parseInt(u.getText().trim());
            int o = Integer.parseInt(ops.getText().trim());
            new Thread(() -> {
                try {
                    Simulador.Resultado r = new Simulador().executar(repositorio, n, o, System.nanoTime());

                    var baos = new java.io.ByteArrayOutputStream();
                    var ps = new java.io.PrintStream(baos, true, java.nio.charset.StandardCharsets.UTF_8);
                    Simulador.imprimir(r, ps);
                    ps.flush();
                    String texto = baos.toString(java.nio.charset.StandardCharsets.UTF_8);

                    SwingUtilities.invokeLater(() -> {
                        saida.setText(texto);
                        saida.setCaretPosition(0);
                        b.setEnabled(true);
                        simulando.set(false);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        saida.setText("Erro na simulação: " + ex);
                        b.setEnabled(true);
                        simulando.set(false);
                    });
                }
            }, "simulador-gui").start();
        });

        g.gridx = 0; g.gridy = 2; g.gridwidth = 2; p.add(b, g);
        g.gridy = 3; p.add(new JScrollPane(saida), g);
        return p;
    }

    // ------------------------------------------------------------- ações

    private void criarEvento() {
        try {
            String nome = campoNome.getText().trim();
            String local = campoLocal.getText().trim();
            BigDecimal preco = new BigDecimal(campoPreco.getText().trim().replace(',', '.'))
                    .setScale(2, RoundingMode.HALF_UP);
            int cap = Integer.parseInt(campoCapacidade.getText().trim());
            EventoDados e = repositorio.adicionarEvento(nome, local, preco, cap);
            logar("Evento criado: id=" + e.id() + " (" + e.nome() + ")");
            campoNome.setText(""); campoLocal.setText(""); campoPreco.setText(""); campoCapacidade.setText("");
            atualizarTabela();
        } catch (NegocioException ex) {
            erro(ex.getMessage());
        } catch (NumberFormatException ex) {
            erro("Valor numérico inválido.");
        }
    }

    private void removerEvento() {
        Long id = lerLong(campoEventoId.getText());
        if (id == null) return;
        try {
            EventoDados e = repositorio.removerEvento(id);
            logar("Removido: " + e.nome() + " (" + e.vendas().size() + " venda(s) descartada(s))");
            atualizarTabela();
        } catch (NegocioException ex) {
            erro(ex.getMessage());
        }
    }

    private void comprar() {
        Long id = lerLong(campoEventoId.getText());
        if (id == null) return;
        String cliente = campoCliente.getText().trim();
        int qtd;
        try {
            qtd = Integer.parseInt(campoQtd.getText().trim());
        } catch (NumberFormatException ex) {
            erro("Quantidade inválida."); return;
        }
        try {
            Venda v = repositorio.comprar(id, cliente, qtd);
            logar(String.format("Compra %d: %d ingresso(s) para %s, total %s",
                    v.id(), v.quantidade(), v.cliente(), MOEDA.format(v.total())));
            campoQtd.setText("");
            atualizarTabela();
        } catch (NegocioException ex) {
            erro(ex.getMessage());
        }
    }

    private void cancelarUltima() {
        Long id = lerLong(campoEventoId.getText());
        if (id == null) return;
        String cliente = campoCliente.getText().trim();
        try {
            EventoDados e = repositorio.buscarPorId(id);
            Venda ultima = null;
            for (Venda v : e.vendas()) {
                if (v.cliente().equalsIgnoreCase(cliente)) ultima = v;
            }
            if (ultima == null) { erro("Nenhuma venda desse cliente no evento " + id + "."); return; }
            Venda c = repositorio.cancelarCompra(id, ultima.id());
            logar("Compra " + c.id() + " cancelada (" + c.quantidade() + " ingresso(s) devolvido(s)).");
            atualizarTabela();
        } catch (NegocioException ex) {
            erro(ex.getMessage());
        }
    }

    private void buscar() {
        termoBuscaAtivo = campoBusca.getText().trim();
        atualizarTabela();   // centraliza: sempre decide com base no termo
    }

    private void salvar() {
        try {
            repositorio.salvar();
            logar("Salvo em " + armazenamento.arquivo() + ".");
        } catch (NegocioException ex) {
            erro(ex.getMessage());
        }
    }

    private void carregar() {
        try {
            if (!armazenamento.existe()) { logar("Ainda não existe arquivo salvo."); return; }
            repositorio.importar(armazenamento.carregar());
            logar("Dados carregados de " + armazenamento.arquivo());
            atualizarTabela();
        } catch (IOException | NegocioException ex) {
            erro("Não foi possível carregar: " + ex.getMessage());
        }
    }

    private void carregarSeExistir() {
        try {
            if (armazenamento.existe()) {
                repositorio.importar(armazenamento.carregar());
                logar("Dados carregados de " + armazenamento.arquivo());
            } else {
                logar("Sem dados em " + armazenamento.arquivo() + ". Crie eventos ou clique em Carregar.");
            }
            atualizarTabela();
        } catch (IOException | NegocioException ex) {
            logar("Falha ao carregar dados iniciais: " + ex.getMessage());
        }
    }

    private void atualizarTabela() {
        if (termoBuscaAtivo.isEmpty()) {
            modelo.setEventos(repositorio.listar());
        } else {
            modelo.setEventos(repositorio.buscarPorNome(termoBuscaAtivo));
        }
    }


    private void iniciarAtualizacaoAutomatica() {
        // Refresca a tabela a cada 1s, para ver mudanças vindas de outras operações.
        Timer t = new Timer(1000, e -> atualizarTabela());
        t.setRepeats(true);
        t.start();
    }

    // ------------------------------------------------------------- utilidades

    private Long lerLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            erro("Id inválido.");
            return null;
        }
    }

    private void logar(String msg) {
        SwingUtilities.invokeLater(() -> {
            log.append(msg + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    private void erro(String msg) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, msg, "Erro", JOptionPane.ERROR_MESSAGE));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignorada) { }
            ArmazenamentoCsv csv = new ArmazenamentoCsv(Path.of("dados", "ingressos.csv"));
            new JanelaPrincipal(new IngressoService(csv), csv).setVisible(true);
        });
    }

    // ------------------------------------------------------------- modelo da tabela
@SuppressWarnings("serial")
    private static final class ModeloTabela extends AbstractTableModel {
        private final String[] colunas = {"ID", "Evento", "Local", "Preço", "Disponíveis", "Vendidos", "Capacidade"};
        private List<EventoDados> eventos = new ArrayList<>();

        void setEventos(List<EventoDados> novos) {
            this.eventos = new ArrayList<>(novos);
            fireTableDataChanged();
        }

        EventoDados eventoNaLinha(int linha) {
            return linha >= 0 && linha < eventos.size() ? eventos.get(linha) : null;
        }

        @Override public int getRowCount() { return eventos.size(); }
        @Override public int getColumnCount() { return colunas.length; }
        @Override public String getColumnName(int c) { return colunas[c]; }
        @Override public boolean isCellEditable(int r, int c) { return false; }

        @Override
        public Object getValueAt(int r, int c) {
            EventoDados e = eventos.get(r);
            return switch (c) {
                case 0 -> e.id();
                case 1 -> e.nome();
                case 2 -> e.local();
                case 3 -> MOEDA.format(e.preco());
                case 4 -> e.disponiveis();
                case 5 -> e.vendidos();
                case 6 -> e.capacidade();
                default -> "";
            };
        }
    }
}