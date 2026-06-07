defmodule LiveDashboardWeb.ChannelCase do
  @moduledoc """
  Test case for Phoenix Channels in the live_dashboard app.

  Wraps `Phoenix.ChannelTest` so individual tests just `use ChannelCase` and
  get the assertion helpers and a configured endpoint without boilerplate.
  Mirrors the convention scaffolded by `mix phx.gen`.
  """

  use ExUnit.CaseTemplate

  using do
    quote do
      import Phoenix.ChannelTest
      @endpoint LiveDashboardWeb.Endpoint
    end
  end
end
