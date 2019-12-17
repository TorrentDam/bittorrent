
module.exports = {
  mode: "production",
  entry: [
    'react-hot-loader',
    __dirname + '/src/index.js'
  ]
  ,
  resolve: {
    modules: [
      __dirname + '/node_modules',
      __dirname + '/../out/client/compileJs/dest'
    ],
    alias: {
      'react-dom': '@hot-loader/react-dom',
    }
  },
  module: {
    rules: [
      { test: /\.js$/, exclude: /node_modules/, loaders: ['react-hot-loader/webpack'] }
    ]
  },
  output: {
    path: __dirname + '/../out/client/webpack/dest',
    publicPath: '/',
    filename: 'bundle.js'
  },
  devServer: {
    contentBase: './resources',
    hot: true,
    watchOptions: {
      poll: 200
    }
  }
};
