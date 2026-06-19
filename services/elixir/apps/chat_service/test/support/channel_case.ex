defmodule ChatServiceWeb.ChannelCase do
  @moduledoc """
  Test case for Phoenix Channels in the chat_service app. Mirrors the
  dashboard's ChannelCase — wraps Phoenix.ChannelTest so tests don't
  duplicate the endpoint reference.
  """

  use ExUnit.CaseTemplate

  using do
    quote do
      import Phoenix.ChannelTest
      @endpoint ChatServiceWeb.Endpoint
    end
  end

  # ChatChannel.join reads from the Repo, so each test needs a sandboxed
  # connection. Tagged async tests share the connection in shared mode.
  setup tags do
    pid = Ecto.Adapters.SQL.Sandbox.start_owner!(ChatService.Repo, shared: not tags[:async])
    on_exit(fn -> Ecto.Adapters.SQL.Sandbox.stop_owner(pid) end)
    :ok
  end
end
