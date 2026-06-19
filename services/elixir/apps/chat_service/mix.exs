defmodule ChatService.MixProject do
  use Mix.Project

  def project do
    [
      app: :chat_service,
      version: "0.1.0",
      build_path: "../../_build",
      config_path: "../../config/config.exs",
      deps_path: "../../deps",
      lockfile: "../../mix.lock",
      elixir: "~> 1.17",
      elixirc_paths: elixirc_paths(Mix.env()),
      start_permanent: Mix.env() == :prod,
      test_coverage: [tool: ExCoveralls, summary: [threshold: 70]],
      preferred_cli_env: [coveralls: :test, "coveralls.json": :test],
      deps: deps()
    ]
  end

  # Compile test/support helpers (e.g. ChannelCase) in :test only.
  defp elixirc_paths(:test), do: ["lib", "test/support"]
  defp elixirc_paths(_), do: ["lib"]

  def application do
    [
      mod: {ChatService.Application, []},
      extra_applications: [:logger, :runtime_tools]
    ]
  end

  defp deps do
    [
      {:phoenix, "~> 1.7.14"},
      {:phoenix_live_view, "~> 1.0"},
      {:jason, "~> 1.4"},
      {:bandit, "~> 1.5"},
      {:ecto_sql, "~> 3.12"},
      {:postgrex, ">= 0.0.0"},
      {:broadway, "~> 1.1"},
      {:broadway_kafka, "~> 0.4"},
      {:joken, "~> 2.6"},
      {:opentelemetry, "~> 1.4"},
      {:opentelemetry_api, "~> 1.3"},
      {:open_api_spex, "~> 3.19"}
    ]
  end
end
