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
end
